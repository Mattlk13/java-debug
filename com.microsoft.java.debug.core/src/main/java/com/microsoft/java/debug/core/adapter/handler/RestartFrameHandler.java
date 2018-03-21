/*******************************************************************************
* Copyright (c) 2017 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.core.adapter.handler;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.microsoft.java.debug.core.DebugException;
import com.microsoft.java.debug.core.DebugUtility;
import com.microsoft.java.debug.core.StackFrameUtility;
import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.variables.StackFrameReference;
import com.microsoft.java.debug.core.protocol.Events;
import com.microsoft.java.debug.core.protocol.Events.UserNotificationEvent.NotificationType;
import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Requests.RestartFrameArguments;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.request.StepRequest;

/**
 * Support Eclipse's `Drop To Frame` action, which is restartFrame in VSCode's
 * debug.
 */
public class RestartFrameHandler implements IDebugRequestHandler {

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Requests.Command.RESTARTFRAME);
    }

    @Override
    public CompletableFuture<Response> handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        RestartFrameArguments restartFrameArgs = (RestartFrameArguments) arguments;
        StackFrameReference stackFrameReference = (StackFrameReference) context.getRecyclableIdPool().getObjectById(restartFrameArgs.frameId);

        if (stackFrameReference == null) {
            return AdapterUtils.createAsyncErrorResponse(response, ErrorCode.RESTARTFRAME_FAILURE,
                    String.format("RestartFarme: cannot find the stack frame with frameID %s", restartFrameArgs.frameId));
        }

        if (canRestartFrame(context, stackFrameReference)) {
            try {
                ThreadReference reference = stackFrameReference.getThread();
                popStackFrames(context, reference, stackFrameReference.getDepth());
                stepin(context, reference);
            } catch (DebugException de) {
                context.getProtocolServer().sendEvent(new Events.UserNotificationEvent(NotificationType.ERROR, de.getMessage()));
            }
            return CompletableFuture.completedFuture(response);
        } else {
            context.getProtocolServer().sendEvent(new Events.UserNotificationEvent(NotificationType.ERROR, "Current stack frame doesn't support restart."));
            context.getProtocolServer().sendEvent(new Events.StoppedEvent("restartframe", stackFrameReference.getThread().uniqueID()));
            return AdapterUtils.createAsyncErrorResponse(response, ErrorCode.RESTARTFRAME_FAILURE, "Failed to restart the selected stack frame.");
        }
    }

    private boolean canRestartFrame(IDebugAdapterContext context, StackFrameReference frameReference) {
        ThreadReference reference = frameReference.getThread();
        StackFrame[] frames = context.getStackFrameManager().reloadStackFrames(reference);
        // Current stack frame cannot be the top frame of the thread:
        if (frames.length <= frameReference.getDepth() + 1) {
            return false;
        }

        // Cannot restart frame involved with native call stacks:
        for (int i = 0; i <= frameReference.getDepth() + 1; i++) {
            if (StackFrameUtility.isNative(frames[i])) {
                return false;
            }
        }
        return true;
    }

    private void popStackFrames(IDebugAdapterContext context, ThreadReference thread, int depth) throws DebugException {
        while (depth >= 0) {
            StackFrame[] frames = context.getStackFrameManager().reloadStackFrames(thread);
            StackFrameUtility.pop(frames[0]);
            --depth;
        }
    }

    private void stepin(IDebugAdapterContext context, ThreadReference thread) {
        StepRequest request = DebugUtility.createStepIntoRequest(thread, context.getStepFilters().classNameFilters);
        context.getDebugSession().getEventHub().stepEvents().filter(debugEvent -> request.equals(debugEvent.event.request())).take(1).subscribe(debugEvent -> {
            debugEvent.shouldResume = false;
            // Have to send to events to keep the UI sync with the step in operations:
            context.getProtocolServer().sendEvent(new Events.StoppedEvent("step", thread.uniqueID()));
            context.getProtocolServer().sendEvent(new Events.ContinuedEvent(thread.uniqueID()));
        });
        request.enable();
        thread.resume();
    }
}