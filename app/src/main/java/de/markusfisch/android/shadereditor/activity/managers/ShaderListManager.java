package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.ShaderAdapter;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords.ShaderInfo;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.project.ProjectSessions;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;

public class ShaderListManager {
	public interface Listener {
		void onShaderSelected(long id);

		void onShaderRenamed(long id, @NonNull String name);

		void onAllShadersDeleted();

		void onShadersLoaded(@NonNull List<ShaderInfo> shaders);

		void onOpenFolder();

		void onProjectSelected(@NonNull String id);

		void onProjectRemoved(@NonNull String id);
	}

	private final Activity activity;
	private final DataSource dataSource;
	private final Listener listener;
	private final ShaderAdapter shaderAdapter;
	// Without project mode, both are null.
	@Nullable
	private final ProjectSessions projects;
	@Nullable
	private final ViewGroup recentProjects;
	@Nullable
	private String selectedProjectId;

	// Use an ExecutorService for background tasks and a Handler for the main thread.
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CoalescingReloadGate reloadGate = new CoalescingReloadGate();

	public ShaderListManager(@NonNull Activity activity,
			@NonNull ListView listView,
			@NonNull DataSource dataSource,
			@Nullable ProjectSessions projects,
			@NonNull Listener listener) {
		this.activity = activity;
		this.dataSource = dataSource;
		this.projects = projects;
		this.listener = listener;

		if (projects != null) {
			// Opened folders come first; the list is never empty then.
			View header = activity.getLayoutInflater().inflate(
					R.layout.drawer_projects, listView, false);
			View openFolder = header.findViewById(R.id.open_folder);
			((ImageView) openFolder.findViewById(R.id.project_icon))
					.setImageResource(R.drawable.ic_folder_open);
			((TextView) openFolder.findViewById(R.id.project_name))
					.setText(R.string.open_folder);
			openFolder.findViewById(R.id.project_location)
					.setVisibility(View.GONE);
			openFolder.setOnClickListener(v -> listener.onOpenFolder());
			recentProjects = header.findViewById(R.id.recent_projects);
			listView.addHeaderView(header, null, false);
		} else {
			recentProjects = null;
			listView.setEmptyView(activity.findViewById(R.id.no_shaders));
		}
		listView.setOnItemClickListener(
				(parent, view, position, id) ->
						listener.onShaderSelected(id));
		listView.setOnItemLongClickListener(
				(parent, view, position, id) -> {
					var title = ((ShaderInfo) parent.getItemAtPosition(
							position)).getTitle();
					editShaderName(id, title != null ? title : "");
					return true;
				});

		shaderAdapter = new ShaderAdapter(activity);
		listView.setAdapter(shaderAdapter);
	}

	/**
	 * Loads shaders from the database asynchronously.
	 */
	public void loadShadersAsync() {
		if (executor.isShutdown() ||
				!reloadGate.request()) {
			return;
		}
		submitLoad();
	}

	public void destroy() {
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
	}

	private void submitLoad() {
		try {
			executor.execute(() -> {
				List<ShaderInfo> shaders = dataSource.shader.getShaders(
						ShaderEditorApp.preferences.sortByLastModification());
				List<ProjectSessions.Recent> recent = projects != null
						? projects.recent()
						: null;
				handler.post(() -> onShadersLoaded(shaders, recent));
			});
		} catch (RejectedExecutionException ignored) {
			reloadGate.abort();
		}
	}

	private void onShadersLoaded(List<ShaderInfo> shaders,
			@Nullable List<ProjectSessions.Recent> recent) {
		if (reloadGate.finish()) {
			if (!executor.isShutdown() && isActivityAlive()) {
				submitLoad();
			} else {
				reloadGate.abort();
			}
			return;
		}
		if (!isActivityAlive()) {
			return;
		}
		List<ShaderInfo> shaderList = shaders != null
				? shaders
				: new ArrayList<>();
		listener.onShadersLoaded(shaderList);
		updateAdapter(shaderList);
		if (recent != null) {
			showRecentProjects(recent);
		}
	}

	private boolean isActivityAlive() {
		return !activity.isFinishing() && !activity.isDestroyed();
	}

	private void updateAdapter(List<ShaderInfo> shaders) {
		if (!isActivityAlive()) {
			return;
		}

		View progressBar = activity.findViewById(R.id.progress_bar);
		View noShadersMessage = activity.findViewById(R.id.no_shaders);

		if (progressBar != null) {
			progressBar.setVisibility(View.GONE);
		}

		List<ShaderInfo> data = shaders != null ? shaders : new ArrayList<>();

		if (data.isEmpty()) {
			if (noShadersMessage != null) {
				noShadersMessage.setVisibility(View.VISIBLE);
			}
			listener.onAllShadersDeleted();
		} else if (noShadersMessage != null) {
			noShadersMessage.setVisibility(View.GONE);
		}
		shaderAdapter.setData(data);
	}

	public void setSelectedProjectId(@Nullable String id) {
		selectedProjectId = id;
	}

	private void showRecentProjects(@NonNull List<ProjectSessions.Recent> recent) {
		if (recentProjects == null) {
			return;
		}
		recentProjects.removeAllViews();
		for (var project : recent) {
			View row = activity.getLayoutInflater().inflate(
					R.layout.row_project, recentProjects, false);
			TextView name = row.findViewById(R.id.project_name);
			name.setText(project.unsaved()
					? project.name() + " \u2022"
					: project.name());
			name.setTextColor(ContextCompat.getColor(activity,
					project.id().equals(selectedProjectId)
							? R.color.accent
							: R.color.drawer_text_unselected));
			TextView location = row.findViewById(R.id.project_location);
			location.setText(project.location());
			location.setVisibility(project.location().isEmpty()
					? View.GONE
					: View.VISIBLE);
			row.setOnClickListener(v ->
					listener.onProjectSelected(project.id()));
			row.setOnLongClickListener(v -> {
				new MaterialAlertDialogBuilder(activity)
						.setMessage(activity.getString(
								R.string.remove_recent_project,
								project.name()))
						.setPositiveButton(R.string.remove, (dialog, which) ->
								listener.onProjectRemoved(project.id()))
						.setNegativeButton(android.R.string.cancel, null)
						.show();
				return true;
			});
			recentProjects.addView(row);
		}
	}

	public void setSelectedShaderId(long id) {
		if (shaderAdapter != null) {
			shaderAdapter.setSelectedId(id);
		}
	}

	private void editShaderName(final long id, @NonNull String name) {
		View view = activity.getLayoutInflater().inflate(
				R.layout.dialog_rename_shader, null);
		final EditText nameView = view.findViewById(R.id.name);
		nameView.setText(name);

		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.rename_shader)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					String newName = nameView.getText().toString();
					dataSource.shader.updateShaderName(id, newName);
					listener.onShaderRenamed(id, newName);
					SoftKeyboard.hide(activity, nameView);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}
}
