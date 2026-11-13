package de.markusfisch.android.shadereditor.adapter;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;

public class PresetUniformAdapter extends BaseAdapter implements Filterable {
	interface Strings {
		@NonNull
		String get(@StringRes int id);
	}

	public static final class Uniform {
		public final String type;
		public final String name;

		final String rationale;

		public boolean isSampler() {
			return type.startsWith("sampler");
		}

		private Uniform(String type, String name, String rationale) {
			this.type = type;
			this.name = name;
			this.rationale = rationale;
		}
	}

	private final String uniformFormat;
	private final int typeColor;
	private final List<Uniform> uniforms;
	private final UniformFilter filter = new UniformFilter();

	private List<Uniform> filteredUniforms;

	public PresetUniformAdapter(Context context) {
		uniformFormat = context.getString(R.string.uniform_format);
		typeColor = MaterialColors.getColor(context,
				com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
		uniforms = createUniforms(context::getString);
		filteredUniforms = uniforms;
	}

	@NonNull
	static List<Uniform> createUniforms(@NonNull Strings strings) {
		var builtins = new ArrayList<>(UniformTable.BUILTINS);
		Collections.sort(builtins, (a, b) -> a.getName().compareTo(b.getName()));
		var uniforms = new ArrayList<Uniform>(builtins.size());
		for (UniformDef uniform : builtins) {
			uniforms.add(new Uniform(
					uniform.getType().getKeyword(),
					uniform.getDeclarator(),
					strings.get(uniform.getDescription())));
		}
		return Collections.unmodifiableList(uniforms);
	}

	@Override
	public Filter getFilter() {
		return filter;
	}

	@Override
	public int getCount() {
		return filteredUniforms.size();
	}

	@Override
	public Uniform getItem(int position) {
		return filteredUniforms.get(position);
	}

	@Override
	public long getItemId(int position) {
		return 0;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		if (convertView == null) {
			convertView = LayoutInflater
					.from(parent.getContext())
					.inflate(R.layout.row_preset, parent, false);
		}

		ViewHolder holder = getViewHolder(convertView);
		Uniform uniform = filteredUniforms.get(position);

		// The name is what the row is about; the type is second.
		SpannableString name = new SpannableString(String.format(
				Locale.US,
				uniformFormat,
				uniform.name,
				uniform.type));
		int nameEnd = name.toString().indexOf(uniform.name) +
				uniform.name.length();
		name.setSpan(new ForegroundColorSpan(typeColor), nameEnd,
				name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		holder.name.setText(name);
		holder.rationale.setText(uniform.rationale);

		return convertView;
	}

	private ViewHolder getViewHolder(View view) {
		ViewHolder holder;
		if ((holder = (ViewHolder) view.getTag()) == null) {
			holder = new ViewHolder();
			holder.name = view.findViewById(R.id.name);
			holder.name.setTypeface(ShaderEditorApp.preferences.getFont());
			holder.rationale = view.findViewById(R.id.rationale);
			view.setTag(holder);
		}

		return holder;
	}

	private static final class ViewHolder {
		private TextView name;
		private TextView rationale;
	}

	private class UniformFilter extends Filter {
		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			String filterString = constraint.toString().toLowerCase(
					Locale.getDefault());
			int size = uniforms.size();
			List<Uniform> filtered = new ArrayList<>(size);

			for (int i = 0; i < size; ++i) {
				Uniform currentUniform = uniforms.get(i);
				if (currentUniform.name.contains(filterString)) {
					filtered.add(currentUniform);
				}
			}

			FilterResults results = new FilterResults();
			results.values = filtered;
			results.count = filtered.size();

			return results;
		}

		@Override
		@SuppressWarnings("unchecked")
		protected void publishResults(CharSequence constraint,
				FilterResults results) {
			filteredUniforms = (ArrayList<Uniform>) results.values;
		}
	}
}
