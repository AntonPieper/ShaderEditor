package de.markusfisch.android.shadereditor.activity;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.DirectoryProjectFiles;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.provider.UserFilesProvider;
import de.markusfisch.android.shadereditor.widget.ErrorListModal;

/**
 * Development harness, debug builds only; remove it when the project UI
 * lands. Runs the main.glsl of the folder in the "folder" extra, a path
 * relative to files/user-files, in the fullscreen preview and lists its
 * diagnostics. Starting it again prepares the folder again.
 */
public class ProjectPreviewActivity extends PreviewActivity {
	public static final String FOLDER = "folder";

	private final ExecutorService preparer =
			Executors.newSingleThreadExecutor();
	private DialogFragment errorList;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (isFinishing()) {
			return;
		}
		getShaderView().getEngine().setListener(new ShaderEngine.Listener() {
			@Override
			public void onDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics) {
				runOnUiThread(() -> showDiagnostics(diagnostics));
			}

			@Override
			public void onFramesPerSecond(int fps) {
			}
		});
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		preparer.shutdownNow();
	}

	@Override
	protected boolean setShaderFromIntent(Intent intent) {
		String folder = intent != null ? intent.getStringExtra(FOLDER) : null;
		if (folder == null ||
				!getResources().getBoolean(R.bool.project_mode)) {
			return false;
		}
		var path = ProjectPath.parse(folder);
		if (!path.isSuccess()) {
			runOnUiThread(() -> showDiagnostics(List.of(path.diagnostic())));
			return true;
		}
		var root = new File(UserFilesProvider.getUserFilesDir(this),
				path.value().toString());
		var engine = getShaderView().getEngine();
		float quality = intent.getFloatExtra(QUALITY, 1f);
		preparer.execute(() -> engine.setPlan(Preparation.prepare(
				new DirectoryProjectFiles(root),
				ProjectPath.parse("main.glsl").value(),
				ProjectLimits.DEFAULT,
				builtinNames()), quality));
		return true;
	}

	private void showDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics) {
		if (errorList != null) {
			errorList.dismissAllowingStateLoss();
			errorList = null;
		}
		if (diagnostics.isEmpty() || isFinishing()) {
			return;
		}
		errorList = new ErrorListModal(diagnostics, (file, line) -> {
		});
		errorList.showNow(getSupportFragmentManager(), ErrorListModal.TAG);
	}

	@NonNull
	private static Set<String> builtinNames() {
		var names = new HashSet<String>();
		for (var uniform : UniformTable.BUILTINS) {
			names.add(uniform.getName());
		}
		return names;
	}
}
