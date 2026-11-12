package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords.ShaderInfo;
import de.markusfisch.android.shadereditor.navigation.Item;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Order;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Source;

/** The database shaders as the level Shaders. */
final class LibrarySource implements Source {
	private static final long DAY = 86_400_000L;

	private final Context context;
	private final ShaderListManager.Listener listener;
	private List<ShaderInfo> shaders = List.of();

	LibrarySource(@NonNull Context context,
			@NonNull ShaderListManager.Listener listener) {
		this.context = context;
		this.listener = listener;
	}

	@NonNull
	static Item.Place place(long id) {
		return new Item.Place(Level.LIBRARY, String.valueOf(id));
	}

	void setShaders(@NonNull List<ShaderInfo> shaders) {
		this.shaders = shaders;
	}

	@Override
	public Listing list(Order order) {
		long now = System.currentTimeMillis();
		long wallpaper = ShaderEditorApp.preferences.getWallpaperShader();
		List<Row> rows = new ArrayList<>();
		for (ShaderInfo shader : shaders) {
			long changed = parse(shader.modified());
			String title = shader.getTitle();
			rows.add(new Row(String.valueOf(shader.id()),
					Row.Kind.SHADER,
					Row.Picture.png(shader.thumb()),
					title != null ? title : "",
					describe(changed, now),
					changed,
					null,
					shader.id() == wallpaper
							? EnumSet.of(Row.Mark.WALLPAPER)
							: EnumSet.noneOf(Row.Mark.class),
					EnumSet.noneOf(Row.Action.class)));
		}
		return new Listing(context.getString(R.string.nav_shaders),
				context.getResources().getQuantityString(
						R.plurals.nav_shaders_count, rows.size(), rows.size()),
				false,
				true,
				Listing.Bar.NEW,
				List.of(new Listing.Section(null, order.sort(rows))));
	}

	@Override
	public void open(Row row, boolean run) {
		if (run) {
			listener.onShaderSelected(Long.parseLong(row.key()));
		}
	}

	@Override
	public void act(Row row, Row.Action action) {
	}

	// Today with the time, yesterday, or the date; the year only when
	// it is not this one.
	@Nullable
	private String describe(long changed, long now) {
		if (changed <= 0) {
			return null;
		}
		TimeZone zone = TimeZone.getDefault();
		long days = daysBetween(changed, now, zone);
		if (days == 0 || days == 1) {
			CharSequence day = DateUtils.getRelativeTimeSpanString(
					changed, now, DateUtils.DAY_IN_MILLIS);
			return days == 1 ? day.toString() : day + ", " +
					DateUtils.formatDateTime(context, changed,
							DateUtils.FORMAT_SHOW_TIME);
		}
		Calendar then = Calendar.getInstance(zone);
		then.setTimeInMillis(changed);
		Calendar today = Calendar.getInstance(zone);
		today.setTimeInMillis(now);
		return DateUtils.formatDateTime(context, changed,
				DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH |
						(then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
								? DateUtils.FORMAT_NO_YEAR
								: DateUtils.FORMAT_SHOW_YEAR));
	}

	/** Calendar days from then to now in the given zone. */
	static long daysBetween(long then, long now, @NonNull TimeZone zone) {
		return (now + zone.getOffset(now)) / DAY -
				(then + zone.getOffset(then)) / DAY;
	}

	// The database keeps local time as text.
	private static long parse(@Nullable String modified) {
		if (modified == null) {
			return 0;
		}
		try {
			var date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
					Locale.US).parse(modified);
			return date != null ? date.getTime() : 0;
		} catch (ParseException e) {
			return 0;
		}
	}
}
