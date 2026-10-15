package com.example.salesagent.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Records which tools the model called while answering one question, so the UI can show the
 * evidence trail. Tool calls run on the request thread, so a ThreadLocal is enough. When a tool
 * is invoked from an MCP client no trace is active and recording is a no-op.
 */
public final class ToolTrace {

    public record Call(String tool, String arguments, String result) {
    }

    private static final ThreadLocal<List<Call>> CURRENT = new ThreadLocal<>();

    private ToolTrace() {
    }

    public static void begin() {
        CURRENT.set(new ArrayList<>());
    }

    public static void record(String tool, String arguments, String result) {
        List<Call> calls = CURRENT.get();
        if (calls != null) {
            calls.add(new Call(tool, arguments, result));
        }
    }

    public static List<Call> end() {
        List<Call> calls = CURRENT.get();
        CURRENT.remove();
        return calls == null ? List.of() : calls;
    }
}
