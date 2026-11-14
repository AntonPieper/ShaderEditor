package de.markusfisch.android.shadereditor.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import de.markusfisch.android.shadereditor.R;

/**
 * The licence text of every bundled font, from assets/fonts/ (which is
 * licenses/fonts/ in the repository), under the font's name.
 */
public class FontLicencesSheet extends BottomSheetDialogFragment {
	public static final String TAG = "FontLicencesSheet";

	@Override
	public void onStart() {
		super.onStart();
		if (getDialog() instanceof BottomSheetDialog dialog) {
			BottomSheetBehavior<?> behavior = dialog.getBehavior();
			behavior.setSkipCollapsed(true);
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle state) {
		View view = inflater.inflate(R.layout.font_licences_sheet,
				container, false);
		LinearLayout list = view.findViewById(R.id.licences);
		Context context = requireContext();
		String[] names = getResources().getStringArray(R.array.font_names);
		String[] values = getResources().getStringArray(R.array.font_values);
		String system = getString(R.string.default_font_value);
		for (int i = 0; i < values.length && i < names.length; ++i) {
			// The system font is not part of the app
			if (values[i].equals(system)) {
				continue;
			}
			String text = readLicence(context, values[i]);
			if (text == null) {
				continue;
			}
			View row = inflater.inflate(R.layout.row_font_licence, list,
					false);
			((TextView) row.findViewById(R.id.font_name)).setText(names[i]);
			((TextView) row.findViewById(R.id.licence_text)).setText(text);
			list.addView(row);
		}
		return view;
	}

	@Nullable
	private static String readLicence(@NonNull Context context,
			@NonNull String font) {
		try (InputStream in = context.getAssets().open(
				"fonts/" + font + ".txt")) {
			var out = new ByteArrayOutputStream();
			var buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, n);
			}
			return reflow(new String(out.toByteArray(),
					StandardCharsets.UTF_8));
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Joins the lines the licence files wrap at 80 columns so each
	 * paragraph wraps to the screen. Headings, rules and blank lines stay.
	 */
	@NonNull
	static String reflow(@NonNull String text) {
		var sb = new StringBuilder();
		boolean join = false;
		boolean paragraph = false;
		for (String raw : text.split("\r?\n")) {
			String line = raw.trim();
			if (line.isEmpty()) {
				paragraph = sb.length() > 0;
				join = false;
				continue;
			}
			boolean rule = line.matches("-+");
			if (paragraph) {
				sb.append("\n\n");
			} else if (join && !rule) {
				sb.append(' ');
			} else if (sb.length() > 0) {
				sb.append('\n');
			}
			boolean heading = !join && line.length() <= 30 &&
					line.equals(line.toUpperCase(Locale.ROOT)) &&
					!line.endsWith(".");
			sb.append(line);
			join = !rule && !heading;
			paragraph = false;
		}
		return sb.toString();
	}
}
