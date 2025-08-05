package de.markusfisch.android.shadereditor.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class UniformSensorManager {
	private final SensorDataProvider dataProvider;
	private final List<UniformPlugin> activeUniforms = new ArrayList<>();

	public UniformSensorManager(SensorDataProvider dataProvider) {
		this.dataProvider = dataProvider;
	}

	public void registerUniform(UniformPlugin uniform) {
		if (!activeUniforms.contains(uniform)) {
			activeUniforms.add(uniform);
			recalculateAndSyncSensors();
		}
	}

	public void unregisterUniform(UniformPlugin uniform) {
		if (activeUniforms.remove(uniform)) {
			recalculateAndSyncSensors();
		}
	}

	/**
	 * The core of the new design. It uses streams to figure out the
	 * total set of required sensors and tells the provider to match that state.
	 */
	private void recalculateAndSyncSensors() {
		Set<Integer> allRequiredSensors = activeUniforms.stream()
				.flatMap(uniform -> uniform.getRequiredSensors().stream())
				.collect(Collectors.toSet());

		dataProvider.syncRequiredSensors(allRequiredSensors);
	}

	public void updateShaderUniforms(int program) {
		for (UniformPlugin uniform : activeUniforms) {
			uniform.update(program, dataProvider.getLatestSensorValues());
		}
	}
}