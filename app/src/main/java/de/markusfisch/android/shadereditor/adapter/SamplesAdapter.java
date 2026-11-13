package de.markusfisch.android.shadereditor.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.R;

public class SamplesAdapter extends BaseAdapter {
	private static final int TYPE_SECTION = 0;
	private static final int TYPE_SAMPLE = 1;

	// Holds either a Samples.Section or a Samples.Sample.
	private final List<Object> rows = new ArrayList<>();

	public SamplesAdapter() {
		for (Samples.Section section : Samples.Section.values()) {
			boolean hasHeading = false;
			for (Samples.Sample sample : Samples.ALL) {
				if (sample.section() != section) {
					continue;
				}
				if (!hasHeading) {
					rows.add(section);
					hasHeading = true;
				}
				rows.add(sample);
			}
		}
	}

	@Override
	public int getCount() {
		return rows.size();
	}

	@Override
	public Object getItem(int position) {
		return rows.get(position);
	}

	@Override
	public long getItemId(int position) {
		return 0;
	}

	@Override
	public int getViewTypeCount() {
		return 2;
	}

	@Override
	public int getItemViewType(int position) {
		return rows.get(position) instanceof Samples.Section
				? TYPE_SECTION
				: TYPE_SAMPLE;
	}

	@Override
	public boolean areAllItemsEnabled() {
		return false;
	}

	@Override
	public boolean isEnabled(int position) {
		return getItemViewType(position) == TYPE_SAMPLE;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		Object row = rows.get(position);
		if (row instanceof Samples.Section section) {
			if (convertView == null) {
				convertView = LayoutInflater
						.from(parent.getContext())
						.inflate(R.layout.row_sample_section, parent, false);
			}
			((TextView) convertView).setText(section.titleId);
			return convertView;
		}

		if (convertView == null) {
			convertView = LayoutInflater
					.from(parent.getContext())
					.inflate(R.layout.row_sample, parent, false);
		}

		ViewHolder holder = getViewHolder(convertView);
		Samples.Sample sample = (Samples.Sample) row;

		holder.thumbnail.setImageResource(sample.thumbId());
		holder.name.setText(sample.nameId());
		holder.rationale.setText(sample.rationaleId());

		return convertView;
	}

	private ViewHolder getViewHolder(View view) {
		ViewHolder holder;
		if ((holder = (ViewHolder) view.getTag()) == null) {
			holder = new ViewHolder();
			holder.thumbnail = view.findViewById(R.id.thumbnail);
			holder.thumbnail.setClipToOutline(true);
			holder.name = view.findViewById(R.id.name);
			holder.rationale = view.findViewById(R.id.rationale);
			view.setTag(holder);
		}

		return holder;
	}

	private static final class ViewHolder {
		private ImageView thumbnail;
		private TextView name;
		private TextView rationale;
	}
}
