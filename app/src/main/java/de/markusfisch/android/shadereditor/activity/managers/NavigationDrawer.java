package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuCompat;
import androidx.core.view.MenuItemCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.NavigationAdapter;
import de.markusfisch.android.shadereditor.adapter.Samples;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords.ShaderInfo;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.navigation.Item;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Order;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Source;
import de.markusfisch.android.shadereditor.navigation.Tap;
import de.markusfisch.android.shadereditor.project.ProjectSessions;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.widget.Message;
import de.markusfisch.android.shadereditor.widget.TouchThruDrawerLayout;

/**
 * The drawer as one column of levels. It shows what a source lists and
 * hands taps back to that source.
 */
public class NavigationDrawer implements ShaderList {
	public interface Host {
		void onAddShader();

		void onDuplicateShader(long id);

		void onDeleteShader(long id);

		void onOpenExample(@NonNull Samples.Sample sample);
	}

	private static final String STATE = "navigation_drawer";
	private static final String LEVEL = "level";
	private static final long SLIDE_MILLIS = 200;

	private final AppCompatActivity activity;
	private final DataSource dataSource;
	private final ShaderListManager.Listener listener;
	private final DrawerLayout drawerLayout;
	private final View drawer;
	private final Host host;
	private final View up;
	private final View upSpace;
	private final View menu;
	private final TextView title;
	private final View readOnly;
	private final TextView subtitle;
	private final View lists;
	private final View bar;
	private final MaterialButton newButton;
	private final View openFile;
	private final LibrarySource library;
	private final FoldersSource folders;
	private final Projects projects;
	private final ActivityResultLauncher<Intent> pickFolder;
	private final ActivityResultLauncher<Intent> pickFile;
	private final Map<Level, Source> sources = new HashMap<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	// Folders are read on their own, so a slow one holds up no other
	// work.
	private final ExecutorService listings =
			Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CoalescingReloadGate reloadGate = new CoalescingReloadGate();

	// The list in view and the one the next level slides in with.
	private RecyclerView list;
	private RecyclerView nextList;
	private Level level = Level.LIBRARY;
	private Listing listing;
	private Item.Place open;
	private boolean loaded;
	// The folder opened before whose levels are shown, or null.
	private FolderSource folder;

	public NavigationDrawer(@NonNull AppCompatActivity activity,
			@NonNull DataSource dataSource,
			@NonNull ShaderListManager.Listener listener,
			@NonNull Host host) {
		this.activity = activity;
		this.dataSource = dataSource;
		this.listener = listener;
		this.host = host;
		library = new LibrarySource(activity, dataSource, listener, host);
		sources.put(Level.LIBRARY, library);
		sources.put(Level.EXAMPLES, new ExamplesSource(activity, host));
		folders = new FoldersSource(activity, library);
		sources.put(Level.FOLDERS, folders);
		projects = new Projects(activity);
		pickFolder = activity.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> onPicked(result, true));
		pickFile = activity.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> onPicked(result, false));

		// Takes the place of the old drawer, from the top edge to the
		// bottom edge.
		drawerLayout = holdScreen(activity);
		drawer = activity.getLayoutInflater().inflate(
				R.layout.navigation_drawer, drawerLayout, false);
		drawer.getLayoutParams().width = getDrawerWidth();
		drawerLayout.addView(drawer);
		ViewCompat.setOnApplyWindowInsetsListener(drawer, (v, insets) -> {
			Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() |
					WindowInsetsCompat.Type.displayCutout());
			v.setPadding(bars.left, bars.top, 0, bars.bottom);
			return insets;
		});
		clearSystemBars(activity);

		up = drawer.findViewById(R.id.navigation_up);
		up.setOnClickListener(v -> go(level.up()));
		upSpace = drawer.findViewById(R.id.navigation_up_space);
		menu = drawer.findViewById(R.id.navigation_menu);
		menu.setOnClickListener(v -> showOrderMenu());
		title = drawer.findViewById(R.id.navigation_title);
		readOnly = drawer.findViewById(R.id.navigation_read_only);
		subtitle = drawer.findViewById(R.id.navigation_subtitle);
		lists = drawer.findViewById(R.id.navigation_lists);
		bar = drawer.findViewById(R.id.navigation_bar);
		newButton = drawer.findViewById(R.id.navigation_new);
		newButton.setOnClickListener(v -> {
			if (listing.bar() == Listing.Bar.OPEN) {
				pick(true);
			} else {
				addShader();
			}
		});
		openFile = drawer.findViewById(R.id.navigation_open_file);
		openFile.setOnClickListener(v -> pick(false));
		list = initList(R.id.navigation_list);
		nextList = initList(R.id.navigation_list_next);

		activity.findViewById(R.id.toolbar).setOnClickListener(v ->
				drawerLayout.openDrawer(drawer));
		OnBackPressedCallback back = new OnBackPressedCallback(false) {
			@Override
			public void handleOnBackPressed() {
				close();
			}
		};
		activity.getOnBackPressedDispatcher().addCallback(activity, back);
		drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
			@Override
			public void onDrawerStateChanged(int newState) {
				if (newState != DrawerLayout.STATE_IDLE &&
						!drawerLayout.isDrawerOpen(drawer)) {
					scrollToSelected();
					loadRecent();
				}
			}

			@Override
			public void onDrawerOpened(@NonNull View view) {
				back.setEnabled(true);
			}

			@Override
			public void onDrawerClosed(@NonNull View view) {
				back.setEnabled(false);
				// It opens again where the file in the editor is.
				go(home());
			}
		});
		// A drawer left open before a rotation is opened again without
		// telling the listener, once the layout has restored its state.
		drawer.post(() -> back.setEnabled(drawerLayout.isDrawerOpen(drawer)));

		Bundle state = activity.getSavedStateRegistry()
				.consumeRestoredStateForKey(STATE);
		Level restored = state != null
				? Level.of(state.getString(LEVEL))
				: null;
		if (restored != null) {
			level = restored;
		}
		activity.getSavedStateRegistry().registerSavedStateProvider(STATE,
				() -> {
					Bundle bundle = new Bundle();
					bundle.putString(LEVEL, level.name());
					return bundle;
				});
		loadRecent();
	}

	/** Opens the drawer on the examples. */
	public void showExamples() {
		go(Level.EXAMPLES);
		drawerLayout.openDrawer(drawer);
	}

	@Override
	public void loadShadersAsync() {
		if (executor.isShutdown() || !reloadGate.request()) {
			return;
		}
		submitLoad();
	}

	@Override
	public void setSelectedShaderId(long id) {
		open = id > 0 ? LibrarySource.place(id) : null;
		if (listing != null) {
			refresh();
		}
	}

	@Override
	public void destroy() {
		if (folder != null) {
			folder.close();
		}
		executor.shutdownNow();
		listings.shutdownNow();
		handler.removeCallbacksAndMessages(null);
	}

	@NonNull
	private RecyclerView initList(int id) {
		RecyclerView recyclerView = drawer.findViewById(id);
		recyclerView.setLayoutManager(new LinearLayoutManager(activity));
		recyclerView.setAdapter(new NavigationAdapter(
				new NavigationAdapter.Listener() {
					@Override
					public void onTap(@NonNull Item item) {
						tap(item);
					}

					@Override
					public void onLongPress(@NonNull Item item,
							@NonNull View view) {
						showRowMenu(item, view);
					}

					@Override
					public void onNewShader() {
						addShader();
					}

					@Override
					public void onShowExamples() {
						go(Level.EXAMPLES);
					}
				}));
		return recyclerView;
	}

	private void submitLoad() {
		try {
			executor.execute(() -> {
				library.loadPlaceholder();
				List<ShaderInfo> shaders = dataSource.shader.getShaders(false);
				handler.post(() -> onShadersLoaded(shaders));
			});
		} catch (RejectedExecutionException e) {
			reloadGate.abort();
		}
	}

	private void loadRecent() {
		try {
			executor.execute(() -> {
				List<ProjectSessions.Recent> recent =
						projects.sessions().recent();
				handler.post(() -> {
					folders.setRecent(recent);
					if (level.equals(Level.FOLDERS)) {
						refresh();
					}
				});
			});
		} catch (RejectedExecutionException e) {
			// Destroyed.
		}
	}

	private void pick(boolean folder) {
		Intent intent = new Intent(folder
				? Intent.ACTION_OPEN_DOCUMENT_TREE
				: Intent.ACTION_OPEN_DOCUMENT)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
						Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
						Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		if (!folder) {
			intent.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
		}
		try {
			(folder ? pickFolder : pickFile).launch(intent);
		} catch (ActivityNotFoundException e) {
			Message.show(activity, R.string.nav_cannot_pick);
		}
	}

	// Cancelling the picker changes nothing.
	private void onPicked(@NonNull ActivityResult result, boolean folder) {
		Intent data = result.getData();
		Uri uri = data != null ? data.getData() : null;
		if (result.getResultCode() != Activity.RESULT_OK || uri == null) {
			return;
		}
		if (folder) {
			projects.openTree(uri, data.getFlags(),
					opened -> onOpened(opened, true));
		} else {
			projects.openDocument(uri, data.getFlags(),
					opened -> onOpened(opened, false));
		}
	}

	// The drawer opens on what was picked; nothing starts running.
	private void onOpened(@NonNull Projects.Opened opened, boolean tree) {
		if (!isActivityAlive()) {
			if (opened.project() != null) {
				opened.project().close();
			}
			return;
		}
		if (opened.id() == null) {
			Message.show(activity, tree
					? R.string.nav_cannot_open_folder
					: R.string.nav_cannot_open_file);
			return;
		}
		folderSource(opened.id()).adopt(opened.project(), opened.problem());
		loadRecent();
		go(Level.root(opened.id()));
		drawerLayout.openDrawer(drawer);
	}

	@NonNull
	private FolderSource folderSource(@NonNull String key) {
		if (folder == null || !folder.key().equals(key)) {
			if (folder != null) {
				folder.close();
			}
			folder = new FolderSource(activity, projects, listings, handler,
					key, folders.find(key), () -> {
				if (folder != null && folder.key().equals(level.key())) {
					refresh();
				}
			});
		}
		return folder;
	}

	private void onShadersLoaded(@NonNull List<ShaderInfo> shaders) {
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
		library.setShaders(shaders);
		loaded = true;
		listener.onShadersLoaded(shaders);
		if (shaders.isEmpty()) {
			listener.onAllShadersDeleted();
		}
		refresh();
	}

	private boolean isActivityAlive() {
		return !activity.isFinishing() && !activity.isDestroyed();
	}

	@NonNull
	private Level home() {
		return open != null ? open.level() : Level.LIBRARY;
	}

	private void refresh() {
		listing = list(level);
		showHeader();
		show(list);
	}

	// Slides the next level in from the side it lies on; a level
	// beside this one, or one shown while the drawer is closed, just
	// replaces it.
	private void go(@NonNull Level to) {
		int direction = to.isBelow(level) ? 1 : level.isBelow(to) ? -1 : 0;
		level = to;
		if (folder != null && !folder.key().equals(to.key())) {
			folder.close();
			folder = null;
		} else if (folder != null) {
			folder.keep(to);
		}
		listing = list(to);
		showHeader();
		list.animate().cancel();
		nextList.animate().cancel();
		list.setTranslationX(0);
		nextList.setTranslationX(0);
		nextList.setVisibility(View.GONE);
		if (direction == 0 || !drawerLayout.isDrawerVisible(drawer)) {
			show(list);
			list.scrollToPosition(0);
			return;
		}
		RecyclerView out = list;
		list = nextList;
		nextList = out;
		show(list);
		list.scrollToPosition(0);
		int width = lists.getWidth();
		list.setTranslationX(direction * width);
		list.setVisibility(View.VISIBLE);
		list.animate().translationX(0).setDuration(SLIDE_MILLIS);
		out.animate().translationX(-direction * width)
				.setDuration(SLIDE_MILLIS)
				.withEndAction(() -> {
					out.setVisibility(View.GONE);
					out.setTranslationX(0);
				});
	}

	@NonNull
	private Listing list(@NonNull Level level) {
		return source(level).list(
				ShaderEditorApp.preferences.sortByLastModification()
						? Order.CHANGED
						: Order.BY_NAME);
	}

	private void show(@NonNull RecyclerView list) {
		// What runs is what is open while shaders come from the database.
		adapter(list).setItems(Item.of(listing, level, open, open,
				Collections.emptySet()),
				loaded && listing.notice() == null);
	}

	private void showHeader() {
		boolean canGoUp = level.up() != null;
		up.setVisibility(canGoUp ? View.VISIBLE : View.GONE);
		upSpace.setVisibility(canGoUp ? View.GONE : View.VISIBLE);
		menu.setVisibility(listing.sortable() ? View.VISIBLE : View.GONE);
		title.setText(boldLastPart(listing.title()));
		// A path is shortened at its start, so its last part stays.
		title.setEllipsize(listing.title().contains(" / ")
				? TextUtils.TruncateAt.START
				: TextUtils.TruncateAt.END);
		readOnly.setVisibility(listing.readOnly() ? View.VISIBLE : View.GONE);
		subtitle.setText(listing.subtitle());
		((View) subtitle.getParent()).setVisibility(
				listing.subtitle() == null ? View.GONE : View.VISIBLE);
		bar.setVisibility(listing.bar() == Listing.Bar.NONE
				? View.GONE
				: View.VISIBLE);
		Listing.Bar action = listing.bar();
		newButton.setText(switch (action) {
			case NEW_SHADER -> R.string.add_shader;
			case OPEN -> R.string.nav_open_folder;
			default -> R.string.nav_new;
		});
		newButton.setIconResource(action == Listing.Bar.OPEN
				? R.drawable.ic_folder_open
				: R.drawable.ic_add);
		openFile.setVisibility(action == Listing.Bar.OPEN
				? View.VISIBLE
				: View.GONE);
	}

	// The folder shown in a path stands out.
	@NonNull
	private static CharSequence boldLastPart(@NonNull String path) {
		int last = path.lastIndexOf(" / ");
		if (last < 0) {
			return path;
		}
		SpannableString text = new SpannableString(path);
		text.setSpan(new StyleSpan(Typeface.BOLD), last + 3, path.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		return text;
	}

	@NonNull
	private Source source(@NonNull Level level) {
		String key = level.key();
		if (key != null) {
			return folderSource(key).at(level);
		}
		Source source = sources.get(level);
		return source != null ? source : library;
	}

	private void tap(@NonNull Item item) {
		switch (item.tap()) {
			case ENTER -> go(item.row().enters());
			case OPEN, OPEN_AND_RUN -> {
				if (source(level).open(item.row(),
						item.tap() == Tap.OPEN_AND_RUN)) {
					close();
				}
			}
			case MORE -> {
				if (folder != null) {
					folder.more(level);
				}
			}
			default -> {
			}
		}
	}

	private void addShader() {
		host.onAddShader();
		close();
	}

	private void showOrderMenu() {
		boolean changed = ShaderEditorApp.preferences.sortByLastModification();
		PopupMenu popup = new PopupMenu(drawer.getContext(), menu, Gravity.END);
		Menu orderMenu = popup.getMenu();
		orderMenu.add(Menu.NONE, R.string.nav_order_changed, Menu.NONE,
				R.string.nav_order_changed).setChecked(changed);
		orderMenu.add(Menu.NONE, R.string.nav_order_name, Menu.NONE,
				R.string.nav_order_name).setChecked(!changed);
		// One of them, marked at the end of its row.
		orderMenu.setGroupCheckable(Menu.NONE, true, true);
		popup.setOnMenuItemClickListener(item -> {
			ShaderEditorApp.preferences.setSortByLastModification(
					item.getItemId() == R.string.nav_order_changed);
			refresh();
			return true;
		});
		popup.show();
	}

	private void showRowMenu(@NonNull Item item, @NonNull View view) {
		Row row = item.row();
		if (row.actions().isEmpty()) {
			return;
		}
		PopupMenu popup = new PopupMenu(drawer.getContext(), view, Gravity.END);
		popup.setForceShowIcon(true);
		Menu rowMenu = popup.getMenu();
		// Delete is set apart, in its own group, and in the error colour.
		MenuCompat.setGroupDividerEnabled(rowMenu, true);
		for (Row.Action action : row.actions()) {
			switch (action) {
				case RENAME -> rowMenu.add(Menu.NONE, action.ordinal(),
						Menu.NONE, R.string.nav_rename).setIcon(R.drawable.ic_edit);
				case DUPLICATE -> rowMenu.add(Menu.NONE, action.ordinal(),
						Menu.NONE, R.string.nav_duplicate)
						.setIcon(R.drawable.ic_content_copy);
				case DELETE -> addDestructive(rowMenu, action.ordinal(),
						R.string.nav_delete, R.drawable.ic_delete);
			}
		}
		Source source = source(level);
		popup.setOnMenuItemClickListener(menuItem -> {
			source.act(row, Row.Action.values()[menuItem.getItemId()]);
			return true;
		});
		popup.show();
	}

	private void addDestructive(@NonNull Menu menu, int id, int title,
			int icon) {
		int error = MaterialColors.getColor(drawer,
				androidx.appcompat.R.attr.colorError);
		SpannableString text = new SpannableString(activity.getString(title));
		text.setSpan(new ForegroundColorSpan(error), 0, text.length(), 0);
		MenuItem item = menu.add(1, id, Menu.NONE, text).setIcon(icon);
		MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(error));
	}

	private void scrollToSelected() {
		int position = adapter(list).getSelectedPosition();
		if (position > -1) {
			((LinearLayoutManager) list.getLayoutManager())
					.scrollToPositionWithOffset(position, list.getHeight() / 3);
		}
	}

	private void close() {
		drawerLayout.closeDrawer(drawer);
	}

	// The drawer layout moves out from under the toolbar and holds the
	// whole screen but the picture behind it, so that the drawer runs
	// behind both system bars and its scrim covers the toolbar too.
	@NonNull
	private static TouchThruDrawerLayout holdScreen(
			@NonNull AppCompatActivity activity) {
		TouchThruDrawerLayout drawerLayout = activity.findViewById(
				R.id.drawer_layout);
		View content = activity.findViewById(R.id.content_frame);
		ViewGroup column = (ViewGroup) drawerLayout.getParent();
		int index = column.indexOfChild(drawerLayout);
		drawerLayout.removeAllViews();
		column.removeView(drawerLayout);
		column.addView(content, index, drawerLayout.getLayoutParams());
		View screen = activity.findViewById(R.id.main_coordinator);
		ViewGroup root = (ViewGroup) screen.getParent();
		ViewGroup.LayoutParams params = screen.getLayoutParams();
		index = root.indexOfChild(screen);
		root.removeView(screen);
		drawerLayout.addView(screen, new DrawerLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		root.addView(drawerLayout, index, params);
		// A tap beside the open drawer closes it, also while the code
		// is hidden and touches go through to the shader.
		drawerLayout.setKeepsTouchesWhileOpen(true);
		return drawerLayout;
	}

	// Nothing translucent of the system's may lie over the drawer, so
	// the app draws the bars' backgrounds itself, as from Android 15.
	private static void clearSystemBars(@NonNull AppCompatActivity activity) {
		Window window = activity.getWindow();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			window.setStatusBarColor(Color.TRANSPARENT);
			window.setNavigationBarColor(Color.TRANSPARENT);
			activity.findViewById(R.id.navbar).setVisibility(View.VISIBLE);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			window.setNavigationBarContrastEnforced(false);
		}
	}

	@NonNull
	private static NavigationAdapter adapter(@NonNull RecyclerView list) {
		return (NavigationAdapter) list.getAdapter();
	}

	// The standard drawer, but never closer to the screen's edge.
	private int getDrawerWidth() {
		Resources res = activity.getResources();
		return Math.min(res.getDimensionPixelSize(R.dimen.se_drawer_max_width),
				res.getDisplayMetrics().widthPixels - res.getDimensionPixelSize(
						R.dimen.navigation_drawer_screen_margin));
	}
}
