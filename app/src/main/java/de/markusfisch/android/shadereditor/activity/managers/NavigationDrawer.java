package de.markusfisch.android.shadereditor.activity.managers;

import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import de.markusfisch.android.shadereditor.navigation.Source;
import de.markusfisch.android.shadereditor.navigation.Tap;

/**
 * The drawer as one column of levels. It shows what a source lists and
 * hands taps back to that source.
 */
public class NavigationDrawer implements ShaderList {
	public interface Host {
		void onAddShader();

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
	private final View up;
	private final View upSpace;
	private final TextView title;
	private final View readOnly;
	private final TextView subtitle;
	private final View lists;
	private final View bar;
	private final TextView newButton;
	private final LibrarySource library;
	private final Map<Level, Source> sources = new HashMap<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CoalescingReloadGate reloadGate = new CoalescingReloadGate();

	// The list in view and the one the next level slides in with.
	private RecyclerView list;
	private RecyclerView nextList;
	private Level level = Level.LIBRARY;
	private Listing listing;
	private Item.Place open;

	public NavigationDrawer(@NonNull AppCompatActivity activity,
			@NonNull DataSource dataSource,
			@NonNull ShaderListManager.Listener listener,
			@NonNull Host host) {
		this.activity = activity;
		this.dataSource = dataSource;
		this.listener = listener;
		library = new LibrarySource(activity, listener);
		sources.put(Level.LIBRARY, library);
		sources.put(Level.EXAMPLES, new ExamplesSource(activity, host));
		sources.put(Level.FOLDERS, new FoldersSource(activity, library));

		// Takes the place of the old drawer.
		View old = activity.findViewById(R.id.menu_frame);
		drawerLayout = (DrawerLayout) old.getParent();
		drawerLayout.removeView(old);
		drawer = activity.getLayoutInflater().inflate(
				R.layout.navigation_drawer, drawerLayout, false);
		drawer.getLayoutParams().width = getDrawerWidth();
		drawerLayout.addView(drawer);
		drawerLayout.setScrimColor(ContextCompat.getColor(activity,
				R.color.navigation_scrim));

		up = drawer.findViewById(R.id.navigation_up);
		up.setOnClickListener(v -> go(level.up()));
		upSpace = drawer.findViewById(R.id.navigation_up_space);
		title = drawer.findViewById(R.id.navigation_title);
		readOnly = drawer.findViewById(R.id.navigation_read_only);
		subtitle = drawer.findViewById(R.id.navigation_subtitle);
		lists = drawer.findViewById(R.id.navigation_lists);
		bar = drawer.findViewById(R.id.navigation_bar);
		newButton = drawer.findViewById(R.id.navigation_new);
		newButton.setOnClickListener(v -> {
			host.onAddShader();
			close();
		});
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
		if (state != null) {
			for (Level known : sources.keySet()) {
				if (known.name().equals(state.getString(LEVEL))) {
					level = known;
				}
			}
		}
		activity.getSavedStateRegistry().registerSavedStateProvider(STATE,
				() -> {
					Bundle bundle = new Bundle();
					bundle.putString(LEVEL, level.name());
					return bundle;
				});
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
		executor.shutdownNow();
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
					}
				}));
		return recyclerView;
	}

	private void submitLoad() {
		try {
			executor.execute(() -> {
				List<ShaderInfo> shaders = dataSource.shader.getShaders(false);
				handler.post(() -> onShadersLoaded(shaders));
			});
		} catch (RejectedExecutionException e) {
			reloadGate.abort();
		}
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
		adapter(list).setItems(items());
	}

	// Slides the next level in from the side it lies on; a level
	// beside this one, or one shown while the drawer is closed, just
	// replaces it.
	private void go(@NonNull Level to) {
		int direction = to.isBelow(level) ? 1 : level.isBelow(to) ? -1 : 0;
		level = to;
		listing = list(to);
		showHeader();
		list.animate().cancel();
		nextList.animate().cancel();
		list.setTranslationX(0);
		nextList.setTranslationX(0);
		nextList.setVisibility(View.GONE);
		if (direction == 0 || !drawerLayout.isDrawerVisible(drawer)) {
			adapter(list).setItems(items());
			list.scrollToPosition(0);
			return;
		}
		RecyclerView out = list;
		list = nextList;
		nextList = out;
		adapter(list).setItems(items());
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

	@NonNull
	private List<Item> items() {
		// What runs is what is open while shaders come from the database.
		return Item.of(listing, level, open, open);
	}

	private void showHeader() {
		boolean canGoUp = level.up() != null;
		up.setVisibility(canGoUp ? View.VISIBLE : View.GONE);
		upSpace.setVisibility(canGoUp ? View.GONE : View.VISIBLE);
		title.setText(listing.title());
		readOnly.setVisibility(listing.readOnly() ? View.VISIBLE : View.GONE);
		subtitle.setText(listing.subtitle());
		((View) subtitle.getParent()).setVisibility(
				listing.subtitle() == null ? View.GONE : View.VISIBLE);
		bar.setVisibility(listing.bar() == Listing.Bar.NONE
				? View.GONE
				: View.VISIBLE);
		newButton.setText(listing.bar() == Listing.Bar.NEW_SHADER
				? R.string.add_shader
				: R.string.nav_new);
	}

	@NonNull
	private Source source(@NonNull Level level) {
		Source source = sources.get(level);
		return source != null ? source : library;
	}

	private void tap(@NonNull Item item) {
		switch (item.tap()) {
			case ENTER -> go(item.row().enters());
			case OPEN, OPEN_AND_RUN -> {
				source(level).open(item.row(), item.tap() == Tap.OPEN_AND_RUN);
				close();
			}
			default -> {
			}
		}
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

	@NonNull
	private static NavigationAdapter adapter(@NonNull RecyclerView list) {
		return (NavigationAdapter) list.getAdapter();
	}

	// The standard drawer, but never closer to the screen's edge.
	private int getDrawerWidth() {
		Resources res = activity.getResources();
		return Math.min(res.getDimensionPixelSize(R.dimen.navigation_drawer_width),
				res.getDisplayMetrics().widthPixels - res.getDimensionPixelSize(
						R.dimen.navigation_drawer_screen_margin));
	}
}
