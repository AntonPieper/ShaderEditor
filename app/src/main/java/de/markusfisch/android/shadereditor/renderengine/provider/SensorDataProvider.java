package de.markusfisch.android.shadereditor.renderengine.provider;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import java.util.Collections;
import java.util.Set;

import de.markusfisch.android.shadereditor.renderengine.DataProvider;
import de.markusfisch.android.shadereditor.renderengine.FrameContext;
import de.markusfisch.android.shadereditor.renderengine.ProviderKey;
import de.markusfisch.android.shadereditor.renderengine.UniformTypes;

public class SensorDataProvider implements DataProvider, SensorEventListener {
    // Keys are now defined with their Java class and their corresponding UniformType.
    public static final ProviderKey<float[]> KEY_ROTATION_MATRIX =
            new ProviderKey<>("sensor.rotationMatrix", float[].class, UniformTypes.MAT3);

    public static final ProviderKey<float[]> KEY_INCLINATION_MATRIX =
            new ProviderKey<>("sensor.inclinationMatrix", float[].class, UniformTypes.MAT3);

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final Sensor rotationVectorSensor;

    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private final float[] rotationVector = new float[4];
    private boolean hasGravity = false;
    private boolean hasGeomagnetic = false;
    private boolean hasRotationVector = false;

    public SensorDataProvider(Context context) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        this.rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        sensorManager.unregisterListener(this);
    }

    @NonNull
    @Override
    public Set<ProviderKey<?>> getProvidedKeys() {
        return Set.of(KEY_ROTATION_MATRIX, KEY_INCLINATION_MATRIX);
    }

    @NonNull
    @Override
    public Set<ProviderKey<?>> getDependencies() {
        return Collections.emptySet();
    }

    @Override
    public void update(@NonNull FrameContext context) {
        float[] r = new float[9];
        float[] i = new float[9];
        float[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};

        if (hasGravity && hasGeomagnetic && SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
            context.put(KEY_ROTATION_MATRIX, r);
            context.put(KEY_INCLINATION_MATRIX, i);
        } else if (hasRotationVector) {
            SensorManager.getRotationMatrixFromVector(r, rotationVector);
            context.put(KEY_ROTATION_MATRIX, r);
            context.put(KEY_INCLINATION_MATRIX, identity);
        } else {
            context.put(KEY_ROTATION_MATRIX, identity);
            context.put(KEY_INCLINATION_MATRIX, identity);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
            hasGravity = true;
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, 3);
            hasGeomagnetic = true;
        } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
            System.arraycopy(event.values, 0, rotationVector, 0, 4);
            hasRotationVector = true;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}