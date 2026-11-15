package de.markusfisch.android.shadereditor.fragment;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.BuildConfig;
import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.project.Library;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.widget.Message;

/**
 * Where the shaders are files, the settings export the library as one
 * zip and import such a zip, or a database backup of an older build,
 * told by what the file is and not by its name. While one runs, no
 * other is started, across screens. What it did is always told: by
 * the settings screen in front, with its lists, or by a notice of the
 * system when there is none.
 */
final class LibraryBackup {
	/** The settings screen this is part of. */
	interface Host {
		/** Import uri, a database of an older build, as before. */
		void importDatabase(@NonNull Uri uri);

		/** Whether one runs has changed: see {@link #running}. */
		void onRunning();
	}

	private static final Executor READING =
			Executors.newSingleThreadExecutor();
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	// The system's provider of the Downloads folder.
	private static final String DOWNLOADS =
			"com.android.providers.downloads.documents";
	private static final byte[] SQLITE = "SQLite format 3\u0000".getBytes(
			StandardCharsets.US_ASCII);
	// Made off the main thread: making it reads the disk.
	private static Projects projects;
	private static boolean running;
	// The one of the settings screen in front.
	@Nullable
	private static LibraryBackup front;

	private final Fragment fragment;
	// Of the app.
	private final Context context;
	private final DataSource database;
	private final Host host;
	private final ActivityResultLauncher<String> create;
	private final ActivityResultLauncher<Intent> pick;

	/** Make it in the fragment's onCreate: it asks for results. */
	LibraryBackup(@NonNull Fragment fragment, @NonNull DataSource database,
			@NonNull Host host) {
		this.fragment = fragment;
		this.context = fragment.requireContext().getApplicationContext();
		this.database = database;
		this.host = host;
		create = fragment.registerForActivityResult(
				new ActivityResultContracts.CreateDocument("application/zip") {
					// In Downloads, unless the user goes elsewhere: the
					// dialog opens where it was used last otherwise.
					@NonNull
					@Override
					public Intent createIntent(@NonNull Context context,
							@NonNull String input) {
						Intent intent = super.createIntent(context, input);
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
							intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
									DocumentsContract.buildDocumentUri(
											DOWNLOADS, "downloads"));
						}
						return intent;
					}
				},
				uri -> {
					if (uri != null) {
						export(uri);
					}
				});
		pick = fragment.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> {
					Intent data = result.getData();
					if (result.getResultCode() == Activity.RESULT_OK &&
							data != null && data.getData() != null) {
						read(data.getData());
					}
				});
		front = this;
	}

	void destroy() {
		if (front == this) {
			front = null;
		}
	}

	/** Return whether an export or an import of the library runs. */
	static boolean running() {
		return running;
	}

	/** Make the two rows of the database those of the library. */
	void wire(@NonNull Preference importRow, @NonNull Preference exportRow) {
		exportRow.setTitle(R.string.export_library);
		exportRow.setSummary(R.string.export_library_summary);
		exportRow.setOnPreferenceClickListener(preference -> {
			try {
				create.launch("ShaderEditor-library-" + new SimpleDateFormat(
						"yyyy-MM-dd", Locale.US).format(new Date()) + ".zip");
			} catch (ActivityNotFoundException e) {
				tell(null, context.getString(R.string.library_no_chooser),
						null);
			}
			return true;
		});
		importRow.setTitle(R.string.import_library);
		importRow.setSummary(R.string.import_library_summary);
		importRow.setOnPreferenceClickListener(preference -> {
			Intent choose = new Intent(Intent.ACTION_GET_CONTENT);
			// A database is offered as application/octet-stream by
			// most apps, and a zip often is as well.
			choose.setType("*/*");
			choose.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
					"application/zip",
					"application/x-zip-compressed",
					"application/x-sqlite3",
					"application/vnd.sqlite3",
					"application/octet-stream"
			});
			choose.addCategory(Intent.CATEGORY_OPENABLE);
			pick.launch(Intent.createChooser(choose,
					context.getString(R.string.import_library)));
			return true;
		});
	}

	private void export(@NonNull Uri uri) {
		ContentResolver resolver = context.getContentResolver();
		begin(projects -> projects.exportLibrary(database,
				BuildConfig.VERSION_NAME,
				() -> resolver.openOutputStream(uri), exported -> {
					Library.Exported written = exported.written();
					if (written.problem() != null) {
						// What was begun is of no use.
						READING.execute(() -> {
							try {
								DocumentsContract.deleteDocument(resolver,
										uri);
							} catch (Exception e) {
								// It stays, as the reason says.
							}
						});
						end(context.getString(R.string.export_library),
								context.getString(
										R.string.library_not_exported,
										written.problem()), null);
						return;
					}
					StringBuilder more = new StringBuilder();
					count(more, R.string.library_exported_unmoved,
							exported.unmoved());
					count(more, R.string.library_exported_unsaved,
							exported.unsaved());
					count(more, R.string.library_hidden_left_out,
							written.hidden());
					end(context.getString(R.string.export_library),
							context.getString(R.string.library_exported,
									written.files()), more.toString());
				}));
	}

	// Tell what uri is by its first bytes, and import it as that.
	private void read(@NonNull Uri uri) {
		ContentResolver resolver = context.getContentResolver();
		READING.execute(() -> {
			byte[] head = new byte[SQLITE.length];
			int count = 0;
			String problem = null;
			try (InputStream in = resolver.openInputStream(uri)) {
				for (int n; in != null && count < head.length && (n =
						in.read(head, count, head.length - count)) > -1; ) {
					count += n;
				}
			} catch (IOException | RuntimeException e) {
				problem = e.getMessage();
			}
			boolean zip = count > 1 && head[0] == 'P' && head[1] == 'K';
			boolean sqlite = count == SQLITE.length &&
					Arrays.equals(head, SQLITE);
			String unread = problem;
			MAIN.post(() -> {
				LibraryBackup shown = front;
				if (zip) {
					take(uri);
				} else if (sqlite && shown != null &&
						shown.fragment.isAdded()) {
					shown.host.importDatabase(uri);
				} else {
					String reason = unread != null ? unread
							: context.getString(sqlite
									? R.string.library_left
									: R.string.library_unknown);
					tell(context.getString(R.string.import_library),
							context.getString(R.string.library_not_imported,
									reason), null);
				}
			});
		});
	}

	private void take(@NonNull Uri uri) {
		ContentResolver resolver = context.getContentResolver();
		String title = context.getString(R.string.import_library);
		begin(projects -> projects.importLibrary(
				() -> resolver.openInputStream(uri), imported -> {
					if (imported.problem() != null) {
						end(title, context.getString(
								R.string.library_not_imported,
								imported.problem()), null);
						return;
					}
					StringBuilder more = new StringBuilder();
					if (!imported.renamed().isEmpty()) {
						StringBuilder list = new StringBuilder();
						for (Map.Entry<ProjectPath, ProjectPath> renamed :
								imported.renamed().entrySet()) {
							list.append('\n').append(renamed.getKey())
									.append(" → ")
									.append(renamed.getValue());
						}
						more.append("\n\n").append(context.getString(
								R.string.library_imported_renamed, list));
					}
					if (!imported.kept().isEmpty()) {
						StringBuilder list = new StringBuilder();
						for (ProjectPath kept : imported.kept()) {
							list.append('\n').append(kept);
						}
						more.append("\n\n").append(context.getString(
								R.string.library_imported_kept, list));
					}
					count(more, R.string.library_hidden_skipped,
							imported.hidden());
					if (imported.unkept()) {
						more.append("\n\n").append(context.getString(
								R.string.library_qualities_not_kept));
					}
					end(title, context.getString(R.string.library_imported,
							imported.shaders(), imported.files(),
							imported.there()), more.toString());
				}));
	}

	private void count(@NonNull StringBuilder more, int text, int count) {
		if (count > 0) {
			more.append("\n\n").append(context.getString(text, count));
		}
	}

	private interface Work {
		void with(@NonNull Projects projects);
	}

	private void begin(@NonNull Work work) {
		if (running) {
			return;
		}
		running = true;
		host.onRunning();
		READING.execute(() -> {
			Projects made;
			synchronized (LibraryBackup.class) {
				if (projects == null) {
					projects = new Projects(context);
				}
				made = projects;
			}
			MAIN.post(() -> work.with(made));
		});
	}

	private void end(@NonNull String title, @NonNull String first,
			@Nullable String more) {
		running = false;
		LibraryBackup shown = front;
		if (shown != null) {
			shown.host.onRunning();
		}
		tell(title, first, more);
	}

	// One plain sentence is a line at the bottom. A failure, which
	// names its reason, and a result with more to say are a dialog:
	// a line at the bottom is cut after its second line. Without a
	// screen, the first sentence as a notice of the system.
	private void tell(@Nullable String title, @NonNull String first,
			@Nullable String more) {
		LibraryBackup shown = front;
		Activity activity = shown != null && shown.fragment.isAdded()
				? shown.fragment.requireActivity()
				: null;
		if (activity == null) {
			Toast.makeText(context, first, Toast.LENGTH_LONG).show();
		} else if (more != null && more.isEmpty()) {
			Message.show(activity, first);
		} else {
			new MaterialAlertDialogBuilder(activity)
					.setTitle(title)
					.setMessage(first + (more != null ? more : ""))
					.setPositiveButton(android.R.string.ok, null)
					.show();
		}
	}
}
