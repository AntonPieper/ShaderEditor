package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.EnumSet;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.Samples;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Order;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Source;

/** The top level: the library and the examples. */
final class FoldersSource implements Source {
	private final Context context;
	private final Source library;

	FoldersSource(@NonNull Context context, @NonNull Source library) {
		this.context = context;
		this.library = library;
	}

	@Override
	public Listing list(Order order) {
		Listing shaders = library.list(order);
		return new Listing(context.getString(R.string.nav_folders),
				null,
				false,
				false,
				Listing.Bar.NONE,
				List.of(new Listing.Section(null, List.of(
						folder(Level.LIBRARY, Row.Picture.Icon.LIBRARY,
								shaders.title(), shaders.subtitle()),
						folder(Level.EXAMPLES, Row.Picture.Icon.EXAMPLES,
								context.getString(R.string.load_sample),
								context.getString(R.string.nav_examples_count,
										Samples.ALL.size()))))));
	}

	@Override
	public void open(Row row, boolean run) {
	}

	@Override
	public void act(Row row, Row.Action action) {
	}

	@NonNull
	private static Row folder(@NonNull Level level,
			@NonNull Row.Picture.Icon icon,
			@NonNull String name,
			String detail) {
		return new Row(level.name(), Row.Kind.FOLDER, Row.Picture.of(icon),
				name, detail, 0, level, EnumSet.noneOf(Row.Mark.class),
				EnumSet.noneOf(Row.Action.class));
	}
}
