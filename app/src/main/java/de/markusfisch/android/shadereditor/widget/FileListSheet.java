package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;

// The files and folders of one folder of the open project, one level at
// a time, with a way up; it never leaves the project's folder.
public class FileListSheet {
	public interface Listener {
		// A file, or a folder to list, the project's root when its path
		// is null.
		void onPick(@NonNull OpenProject.Item item);
	}

	private static final String UNSAVED = "\u2022";

	private final List<OpenProject.Item> items = new ArrayList<>();
	private final BottomSheetDialog dialog;
	private final TextView folderPath;
	private final TextView noFiles;
	private final ArrayAdapter<OpenProject.Item> adapter;
	@Nullable
	private ProjectPath folder;
	// The row that leads to the folder above, if any.
	@Nullable
	private OpenProject.Item up;
	@NonNull
	private Set<ProjectPath> unsaved = Set.of();
	@Nullable
	private ProjectPath running;

	public FileListSheet(@NonNull Context context, @NonNull Listener listener) {
		dialog = new BottomSheetDialog(context);
		View view = LayoutInflater.from(context).inflate(R.layout.sheet_files,
				null);
		folderPath = view.findViewById(R.id.folder_path);
		noFiles = view.findViewById(R.id.no_files);
		adapter = new ArrayAdapter<>(context, R.layout.row_file, items) {
			@NonNull
			@Override
			public View getView(int position, @Nullable View row,
					@NonNull ViewGroup parent) {
				if (row == null) {
					row = LayoutInflater.from(context).inflate(
							R.layout.row_file, parent, false);
				}
				bind(row, items.get(position));
				return row;
			}
		};
		ListView list = view.findViewById(R.id.files);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, row, position, id) -> {
			OpenProject.Item item = items.get(position);
			listener.onPick(item == up
					? new OpenProject.Item(parent(folder), true)
					: item);
		});
		dialog.setContentView(view);
		dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
	}

	// Shows the listing of folder, named title, marking the files with
	// unsaved text and the one that runs.
	public void show(@NonNull String title, @Nullable ProjectPath folder,
			@NonNull OpenProject.Folder listing,
			@NonNull Set<ProjectPath> unsaved, @Nullable ProjectPath running) {
		this.folder = folder;
		this.unsaved = unsaved;
		this.running = running;
		items.clear();
		up = folder != null ? new OpenProject.Item(folder, true) : null;
		if (up != null) {
			items.add(up);
		}
		items.addAll(listing.items());
		adapter.notifyDataSetChanged();
		folderPath.setText(title);
		noFiles.setText(listing.problem() != null
				? listing.problem()
				: dialog.getContext().getString(R.string.empty_folder));
		noFiles.setVisibility(listing.items().isEmpty()
				? View.VISIBLE
				: View.GONE);
		dialog.show();
	}

	public void dismiss() {
		dialog.dismiss();
	}

	private void bind(@NonNull View row, @NonNull OpenProject.Item item) {
		ImageView icon = row.findViewById(R.id.file_icon);
		TextView name = row.findViewById(R.id.file_name);
		TextView state = row.findViewById(R.id.file_state);
		ProjectPath path = item.path();
		icon.setImageResource(item.folder()
				? R.drawable.ic_folder
				: path.getKind() == ProjectPath.Kind.IMAGE
						? R.drawable.ic_image
						: R.drawable.ic_file);
		name.setText(item == up ? ".." : path.getFileName());
		String text = path.equals(running)
				? row.getContext().getString(R.string.running)
				: "";
		state.setText(unsaved.contains(path) ? text + " " + UNSAVED : text);
	}

	@Nullable
	private static ProjectPath parent(@Nullable ProjectPath path) {
		String text = path != null ? path.toString() : "";
		int slash = text.lastIndexOf('/');
		return slash > 0 ? ProjectPath.parse(text.substring(0, slash)).value()
				: null;
	}
}
