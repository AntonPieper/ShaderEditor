package de.markusfisch.android.shadereditor.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataRecords.ShaderInfo;

public class ShaderAdapter extends BaseAdapter {
	private record DecodeRequest(long shaderId, int hash, byte[] bytes) {
	}

	private record CachedThumbnail(int hash, Bitmap bitmap) {
	}

	private final int textColorSelected;
	private final int textColorUnselected;
	private final List<ShaderInfo> shaders = new ArrayList<>();
	private final Map<Long, CachedThumbnail> thumbnails = new HashMap<>();
	private final ExecutorService thumbnailDecoder = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private long selectedShaderId = 0;
	private int dataVersion = 0;
	private volatile boolean destroyed = false;

	public ShaderAdapter(Context context) {
		this.textColorSelected = ContextCompat.getColor(context, R.color.accent);
		this.textColorUnselected = ContextCompat.getColor(context,
				R.color.drawer_text_unselected);
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
					R.layout.row_shader,
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

	public void destroy() {
		destroyed = true;
		thumbnailDecoder.shutdownNow();
		handler.removeCallbacksAndMessages(null);
		thumbnails.clear();
		shaders.clear();
	}

	public void setSelectedId(long id) {
		selectedShaderId = id;
		notifyDataSetChanged();
	}

	public void setData(List<ShaderInfo> newShaders) {
		if (destroyed) {
			return;
		}
		this.shaders.clear();
		if (newShaders != null) {
			this.shaders.addAll(newShaders);
		}
		int version = ++dataVersion;
		List<DecodeRequest> requests = updateThumbnails();
		notifyDataSetChanged();
		decodeThumbnailsAsync(version, requests);
	}

	@NonNull
	private List<DecodeRequest> updateThumbnails() {
		Set<Long> currentShaderIds = new HashSet<>();
		List<DecodeRequest> requests = new ArrayList<>();

		for (ShaderInfo shader : shaders) {
			currentShaderIds.add(shader.id());
			byte[] bytes = shader.thumb();
			if (bytes == null || bytes.length == 0) {
				thumbnails.remove(shader.id());
				continue;
			}
			int hash = Arrays.hashCode(bytes);
			CachedThumbnail cached = thumbnails.get(shader.id());
			if (cached != null && cached.hash() == hash) {
				continue;
			}
			requests.add(new DecodeRequest(shader.id(), hash, bytes.clone()));
		}

		var iterator = thumbnails.keySet().iterator();
		while (iterator.hasNext()) {
			if (!currentShaderIds.contains(iterator.next())) {
				iterator.remove();
			}
		}
		return requests;
	}

	private void decodeThumbnailsAsync(int version,
			@NonNull List<DecodeRequest> requests) {
		if (requests.isEmpty() || thumbnailDecoder.isShutdown()) {
			return;
		}
		try {
			thumbnailDecoder.execute(() -> {
				Map<Long, CachedThumbnail> decoded = new HashMap<>();
				for (DecodeRequest request : requests) {
					if (destroyed || Thread.currentThread().isInterrupted()) {
						return;
					}
					Bitmap bitmap = BitmapFactory.decodeByteArray(
							request.bytes(), 0, request.bytes().length);
					if (bitmap != null) {
						decoded.put(request.shaderId(),
								new CachedThumbnail(request.hash(), bitmap));
					}
				}
				if (destroyed || decoded.isEmpty()) {
					return;
				}
				handler.post(() -> applyDecodedThumbnails(version, decoded));
			});
		} catch (RejectedExecutionException ignored) {
			// Ignore adapter shutdown races.
		}
	}

	private void applyDecodedThumbnails(int version,
			@NonNull Map<Long, CachedThumbnail> decoded) {
		if (destroyed || version != dataVersion) {
			return;
		}
		thumbnails.putAll(decoded);
		notifyDataSetChanged();
	}

	private class ViewHolder {
		private final ImageView icon;
		private final TextView title;

		ViewHolder(@NonNull View itemView) {
			icon = itemView.findViewById(R.id.shader_icon);
			title = itemView.findViewById(R.id.shader_title);
		}

		void bindTo(@NonNull final ShaderInfo shader) {
			CachedThumbnail cached = thumbnails.get(shader.id());
			if (cached != null) {
				icon.setImageBitmap(cached.bitmap());
			} else {
				icon.setImageResource(R.drawable.thumbnail_new_shader);
			}

			title.setText(shader.getTitle());
			title.setTextColor(shader.id() == selectedShaderId ? textColorSelected :
					textColorUnselected);
		}
	}
}
