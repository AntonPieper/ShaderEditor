package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;

final class ActiveBuiltins {
	private static final class GlOutput implements UniformOutput {
		private final float[] vec2 = new float[2];
		@NonNull
		private final GlDevice device;
		private int location;

		private GlOutput(@NonNull GlDevice device) {
			this.device = device;
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
		public void setVec3Array(@NonNull float[] values, int count) {
			device.uniform3fv(location, count, values);
		}
	}

	private record Slot(@NonNull UniformDef uniform, int location) {
	}

	@NonNull
	private final GlDevice device;
	@NonNull
	private final GlProgram program;
	@NonNull
	private final GlOutput output;
	@NonNull
	private final Slot[] slots;

	ActiveBuiltins(@NonNull GlDevice device, @NonNull GlProgram program) {
		this.device = device;
		this.program = program;
		output = new GlOutput(device);
		var active = new ArrayList<Slot>();
		for (var uniform : UniformTable.BUILTINS) {
			GlUniform reflected = program.getUniforms().get(uniform.getName());
			if (reflected != null && reflected.location() > -1) {
				active.add(new Slot(uniform, reflected.location()));
			}
		}
		slots = active.toArray(new Slot[0]);
	}

	void write(@NonNull FrameInputs inputs) {
		device.useProgram(program);
		for (var slot : slots) {
			output.location = slot.location();
			slot.uniform().getWriter().write(inputs, output);
		}
	}
}
