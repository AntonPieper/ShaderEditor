package de.markusfisch.android.shadereditor.adapter;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataRecords.ShaderInfo;

public class ShaderAdapter extends BaseAdapter {
	private final int rowLayout;
	private final int textColorSelected;
	private final int textColorUnselected;
	private final List<ShaderInfo> shaders = new ArrayList<>();
	private long selectedShaderId = 0;

	public ShaderAdapter(Context context) {
		this(context, R.layout.row_shader);
	}

	/** A row with a radio button shows the selection by it. */
	public ShaderAdapter(Context context, @LayoutRes int rowLayout) {
		this.rowLayout = rowLayout;
		this.textColorSelected = MaterialColors.getColor(context,
				com.google.android.material.R.attr.colorOnSurface, 0);
		this.textColorUnselected = MaterialColors.getColor(context,
				com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
	}

	@Override
	public int getCount() {
		return shaders.size();
	}

	@Override
	public DataRecords.ShaderInfo getItem(int position) {
		return shaders.get(position);
	}

	@Override
	public long getItemId(int position) {
		return shaders.get(position).id();
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		ViewHolder holder;
		if (convertView == null) {
			convertView = LayoutInflater.from(parent.getContext()).inflate(
					rowLayout,
					parent,
					false);
			holder = new ViewHolder(convertView);
			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}
		holder.bindTo(shaders.get(position));
		return convertView;
	}

	public void setSelectedId(long id) {
		selectedShaderId = id;
		notifyDataSetChanged();
	}

	public void setData(List<ShaderInfo> newShaders) {
		this.shaders.clear();
		if (newShaders != null) {
			this.shaders.addAll(newShaders);
		}
		notifyDataSetChanged();
	}

	private class ViewHolder {
		private final ImageView icon;
		private final TextView title;
		@Nullable
		private final RadioButton selected;

		ViewHolder(@NonNull View itemView) {
			icon = itemView.findViewById(R.id.shader_icon);
			title = itemView.findViewById(R.id.shader_title);
			selected = itemView.findViewById(R.id.shader_selected);
		}

		void bindTo(@NonNull final ShaderInfo shader) {
			byte[] bytes = shader.thumb();
			if (bytes != null && bytes.length > 0) {
				icon.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
			} else {
				icon.setImageResource(R.drawable.thumbnail_new_shader);
			}

			title.setText(shader.getTitle());
			boolean isSelected = shader.id() == selectedShaderId;
			if (selected != null) {
				selected.setChecked(isSelected);
				return;
			}
			title.setTextColor(isSelected ? textColorSelected :
					textColorUnselected);
		}
	}
}