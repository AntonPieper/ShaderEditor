package de.markusfisch.android.shadereditor.activity.managers;

import android.content.res.Resources;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.NavigationAdapter;
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
	}

	private final AppCompatActivity activity;
	private final DataSource dataSource;
	private final ShaderListManager.Listener listener;
	private final Host host;
	private final DrawerLayout drawerLayout;
	private final View drawer;
	private final TextView title;
	private final TextView subtitle;
	private final RecyclerView list;
	private final NavigationAdapter adapter;
	private final View bar;
	private final LibrarySource library;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CoalescingReloadGate reloadGate = new CoalescingReloadGate();

	private final Level level = Level.LIBRARY;
	private Listing listing;
	private Item.Place open;

	public NavigationDrawer(@NonNull AppCompatActivity activity,
			@NonNull DataSource dataSource,
			@NonNull ShaderListManager.Listener listener,
			@NonNull Host host) {
		this.activity = activity;
		this.dataSource = dataSource;
		this.listener = listener;
		this.host = host;
		library = new LibrarySource(activity, listener);

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

		title = drawer.findViewById(R.id.navigation_title);
		subtitle = drawer.findViewById(R.id.navigation_subtitle);
		bar = drawer.findViewById(R.id.navigation_bar);
		drawer.findViewById(R.id.navigation_new).setOnClickListener(v -> {
			host.onAddShader();
			close();
		});
		adapter = new NavigationAdapter(new NavigationAdapter.Listener() {
			@Override
			public void onTap(@NonNull Item item) {
				tap(item);
			}

			@Override
			public void onLongPress(@NonNull Item item, @NonNull View view) {
			}
		});
		list = drawer.findViewById(R.id.navigation_list);
		list.setLayoutManager(new LinearLayoutManager(activity));
		list.setAdapter(adapter);

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
			}
		});
		// A drawer left open before a rotation is opened again without
		// telling the listener, once the layout has restored its state.
		drawer.post(() -> back.setEnabled(drawerLayout.isDrawerOpen(drawer)));
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
			show();
		}
	}

	@Override
	public void destroy() {
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
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

	private void refresh() {
		listing = source(level).list(
				ShaderEditorApp.preferences.sortByLastModification()
						? Order.CHANGED
						: Order.BY_NAME);
		show();
	}

	private void show() {
		title.setText(listing.title());
		subtitle.setText(listing.subtitle());
		subtitle.setVisibility(listing.subtitle() == null
				? View.GONE
				: View.VISIBLE);
		bar.setVisibility(listing.bar() == Listing.Bar.NONE
				? View.GONE
				: View.VISIBLE);
		// What runs is what is open while shaders come from the database.
		adapter.setItems(Item.of(listing, level, open, open));
	}

	@NonNull
	private Source source(@NonNull Level level) {
		return library;
	}

	private void tap(@NonNull Item item) {
		switch (item.tap()) {
			case OPEN, OPEN_AND_RUN -> {
				source(level).open(item.row(), item.tap() == Tap.OPEN_AND_RUN);
				close();
			}
			default -> {
			}
		}
	}

	private void scrollToSelected() {
		int position = adapter.getSelectedPosition();
		if (position > -1) {
			((LinearLayoutManager) list.getLayoutManager())
					.scrollToPositionWithOffset(position, list.getHeight() / 3);
		}
	}

	private void close() {
		drawerLayout.closeDrawer(drawer);
	}

	// The standard drawer, but never closer to the screen's edge.
	private int getDrawerWidth() {
		Resources res = activity.getResources();
		return Math.min(res.getDimensionPixelSize(R.dimen.navigation_drawer_width),
				res.getDisplayMetrics().widthPixels - res.getDimensionPixelSize(
						R.dimen.navigation_drawer_screen_margin));
	}
}
