package com.wangzan101.mika.releaseprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

public final class TaskRunArtProbe extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> task = Class.forName("com.wangzan101.mika.task.TaskRun", true, loader);
            Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", false, loader);
            task.getDeclaredMethod("execute", List.class, continuation);
            result.putString("artVerified", "true");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            result.putString("stack", stack.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
