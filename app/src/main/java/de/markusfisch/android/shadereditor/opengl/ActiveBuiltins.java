package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
	private final Output output;
	@NonNull
	private final Slot[] slots;

	ActiveBuiltins(
			@NonNull GlDevice device,
			@NonNull GlProgram program,
			@NonNull UniformSources sources) {
		this(UniformTable.BUILTINS, program.getUniforms(), sources,
				new GlOutput(device, program));
	}

	ActiveBuiltins(
			@NonNull List<UniformDef> table,
			@NonNull Map<String, GlUniform> reflected,
			@NonNull UniformSources sources,
			@NonNull Output output) {
		this.sources = sources;
		this.output = output;
		var active = new ArrayList<Slot>();
		for (var uniform : table) {
			GlUniform uniformSlot = reflected.get(uniform.getName());
			if (uniformSlot == null ||
					uniformSlot.location() < 0 ||
					!uniform.fits(uniformSlot)) {
				continue;
			}
			var key = uniform.getSource();
			var source = key != null ? sources.start(key) : null;
			if (!uniform.isDeclarationOnly()) {
				active.add(new Slot(uniform, uniformSlot.location(), source));
			}
		}
		slots = active.toArray(new Slot[0]);
	}

	void write(@NonNull FrameInputs inputs, long now) {
		sources.update(now);
		output.begin();
		for (var slot : slots) {
			output.setLocation(slot.location());
			slot.uniform().write(inputs, slot.source(), output);
		}
	}
}
