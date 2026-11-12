package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

// The builtins the programs of a build read, with the sources they need
// running once for all of them.
final class ActiveBuiltins {
	interface Output extends UniformOutput {
		void begin();

		void setLocation(int location);
	}

	private static final class GlOutput implements Output {
		private final float[] vec2 = new float[2];
		private final float[] vec3 = new float[3];
		private final float[] vec4 = new float[4];
		@NonNull
		private final GlDevice device;
		@NonNull
		private final GlProgram program;
		private int location;

		private GlOutput(@NonNull GlDevice device, @NonNull GlProgram program) {
			this.device = device;
			this.program = program;
		}

		@Override
		public void begin() {
			device.useProgram(program);
		}

		@Override
		public void setLocation(int location) {
			this.location = location;
		}

		@Override
		public void setInt(int value) {
			device.uniform1i(location, value);
		}

		@Override
		public void setFloat(float value) {
			device.uniform1f(location, value);
		}

		@Override
		public void setVec2(float x, float y) {
			vec2[0] = x;
			vec2[1] = y;
			device.uniform2fv(location, 1, vec2);
		}

		@Override
		public void setVec3(float x, float y, float z) {
			vec3[0] = x;
			vec3[1] = y;
			vec3[2] = z;
			device.uniform3fv(location, 1, vec3);
		}

		@Override
		public void setVec4(float x, float y, float z, float w) {
			vec4[0] = x;
			vec4[1] = y;
			vec4[2] = z;
			vec4[3] = w;
			device.uniform4fv(location, 1, vec4);
		}

		@Override
		public void setVec3Array(@NonNull float[] values, int count) {
			device.uniform3fv(location, count, values);
		}

		@Override
		public void setMat2(@NonNull float[] values) {
			device.uniformMatrix2fv(location, values);
		}

		@Override
		public void setMat3(@NonNull float[] values) {
			device.uniformMatrix3fv(location, values);
		}
	}

	private record Slot(
			@NonNull UniformDef uniform,
			int location,
			@Nullable UniformSource source) {
	}

	@NonNull
	private final UniformSources sources;
	@NonNull
	private final List<Output> outputs;
	// Per program.
	@NonNull
	private final Slot[][] slots;

	ActiveBuiltins(
			@NonNull GlDevice device,
			@NonNull List<PlanPass> passes,
			@NonNull UniformSources sources) {
		this(UniformTable.BUILTINS, uniforms(passes), sources,
				outputs(device, passes));
	}

	ActiveBuiltins(
			@NonNull List<UniformDef> table,
			@NonNull Map<String, GlUniform> reflected,
			@NonNull UniformSources sources,
			@NonNull Output output) {
		this(table, List.of(reflected), sources, List.of(output));
	}

	ActiveBuiltins(
			@NonNull List<UniformDef> table,
			@NonNull List<Map<String, GlUniform>> reflected,
			@NonNull UniformSources sources,
			@NonNull List<Output> outputs) {
		this.sources = sources;
		this.outputs = outputs;
		var keys = new LinkedHashSet<UniformSource.Key<?>>();
		var active = new ArrayList<List<Slot>>();
		for (var uniforms : reflected) {
			var program = new ArrayList<Slot>();
			active.add(program);
			for (var uniform : table) {
				GlUniform uniformSlot = uniforms.get(uniform.getName());
				if (uniformSlot == null ||
						uniformSlot.location() < 0 ||
						!uniform.fits(uniformSlot)) {
					continue;
				}
				if (uniform.getSource() != null) {
					keys.add(uniform.getSource());
				}
				if (!uniform.isDeclarationOnly()) {
					program.add(new Slot(uniform, uniformSlot.location(), null));
				}
			}
		}
		// Runs what these shaders read and stops what they do not.
		sources.run(keys);
		slots = new Slot[active.size()][];
		for (int p = 0; p < slots.length; ++p) {
			var program = active.get(p);
			slots[p] = new Slot[program.size()];
			for (int i = 0; i < slots[p].length; ++i) {
				var slot = program.get(i);
				var key = slot.uniform().getSource();
				slots[p][i] = new Slot(slot.uniform(), slot.location(),
						key != null ? sources.start(key) : null);
			}
		}
	}

	// Of the first program.
	void write(@NonNull FrameInputs inputs, long now) {
		write(0, inputs, now);
	}

	void write(int program, @NonNull FrameInputs inputs, long now) {
		sources.update(now);
		var output = outputs.get(program);
		output.begin();
		for (var slot : slots[program]) {
			output.setLocation(slot.location());
			slot.uniform().write(inputs, slot.source(), output);
		}
	}

	@NonNull
	private static List<Map<String, GlUniform>> uniforms(
			@NonNull List<PlanPass> passes) {
		var uniforms = new ArrayList<Map<String, GlUniform>>();
		for (var pass : passes) {
			uniforms.add(pass.uniforms);
		}
		return uniforms;
	}

	@NonNull
	private static List<Output> outputs(
			@NonNull GlDevice device,
			@NonNull List<PlanPass> passes) {
		var outputs = new ArrayList<Output>();
		for (var pass : passes) {
			outputs.add(new GlOutput(device, pass.program));
		}
		return outputs;
	}
}
