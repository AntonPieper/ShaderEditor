package de.markusfisch.android.shadereditor.renderengine;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;

public class RenderEngine {
    @NonNull
    private final Context applicationContext;
    @NonNull
    private final List<DataProvider> sortedProviders;

    public RenderEngine(@NonNull Context context, @NonNull LifecycleOwner lifecycleOwner, @NonNull List<DataProvider> providers) {
        this.applicationContext = context.getApplicationContext();
        this.sortedProviders = sortProviders(providers);
        for (DataProvider provider : this.sortedProviders) {
            lifecycleOwner.getLifecycle().addObserver(provider);
        }
    }

    @NonNull
    @Contract("_, _ -> new")
    public List<UniformCommand> prepareUniforms(int shaderProgram, @NonNull List<ShaderBinding<?>> bindings) {
        FrameContext frameContext = new FrameContext(applicationContext);

        // 1. Run all data providers to populate the context
        for (DataProvider provider : sortedProviders) {
            provider.update(frameContext);
        }

        // 2. Create uniform commands based on the shader's needs
        List<UniformCommand> commands = new ArrayList<>();
        for (ShaderBinding<?> binding : bindings) {
            // Use the generic helper method to process each binding in a type-safe way
            addUniformCommand(commands, frameContext, shaderProgram, binding);
        }
        return commands;
    }

    /**
     * A generic helper method to process a single shader binding in a type-safe context.
     */
    private <T> void addUniformCommand(
            @NonNull List<UniformCommand> commands,
            @NonNull FrameContext context,
            int shaderProgram,
            @NonNull ShaderBinding<T> binding
    ) {
        ProviderKey<T> key = binding.key();
        UniformType<T> applicator = key.uniformType;

        if (applicator == null) {
            return; // This key is not meant for a uniform
        }

        T value = context.get(key);
        if (value == null) {
            return; // Value not available in this frame
        }

        int location = GLES20.glGetUniformLocation(shaderProgram, binding.uniformName());
        if (location != -1) {
            // This call is 100% type-safe at compile time. No casts needed.
            commands.add(applicator.createCommand(location, value));
        }
    }

    @NonNull
    @Contract("_ -> new")
    private List<DataProvider> sortProviders(List<DataProvider> providers) {
        // A real implementation requires a topological sort (e.g., Kahn's algorithm).
        // For this example, we assume the initial list is already correctly ordered.
        return new ArrayList<>(providers);
    }
}
