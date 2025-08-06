package de.markusfisch.android.shadereditor.renderengine;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;

import java.util.Set;

/**
 * The main plugin interface.
 */
public interface DataProvider extends DefaultLifecycleObserver {
    @NonNull
    Set<ProviderKey<?>> getProvidedKeys();

    @NonNull
    Set<ProviderKey<?>> getDependencies();

    void update(@NonNull FrameContext context);
}