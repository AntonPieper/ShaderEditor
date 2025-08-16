package de.markusfisch.android.shadereditor.activity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.markusfisch.android.shadereditor.engine.error.EngineError;

/**
 * A Parcelable DTO (Data Transfer Object) to transport engine error
 * information between activities via Intents.
 */
public class ParcelableEngineError implements Parcelable {
	public static final Creator<ParcelableEngineError> CREATOR = new Creator<>() {
		@NonNull
		@Contract("_ -> new")
		@Override
		public ParcelableEngineError createFromParcel(Parcel in) {
			return new ParcelableEngineError(in);
		}

		@NonNull
		@Contract(value = "_ -> new", pure = true)
		@Override
		public ParcelableEngineError[] newArray(int size) {
			return new ParcelableEngineError[size];
		}
	};
	private final String message;
	private final ArrayList<ParcelableSourceLocation> locations;

	public ParcelableEngineError(@NonNull EngineError error) {
		this.message = error.message();
		this.locations = new ArrayList<>();

		if (error instanceof EngineError.ShaderCompilationError sce) {
			for (var loc : sce.sourceLocations()) {
				this.locations.add(new ParcelableSourceLocation(loc));
			}
		} else {
			// For other error types, add the main message as a non-line-specific item.
			this.locations.add(
					new ParcelableSourceLocation(new EngineError.SourceLocation(
							-1, error.message())));
		}
	}

	protected ParcelableEngineError(@NonNull Parcel in) {
		message = in.readString();
		locations = in.createTypedArrayList(ParcelableSourceLocation.CREATOR);
	}

	public EngineError toEngineError() {
		List<EngineError.SourceLocation> sourceLocations = locations.stream()
				.map(ParcelableSourceLocation::toSourceLocation)
				.collect(Collectors.toList());

		// Reconstruct a ShaderCompilationError, as it's the most detailed type.
		// Other errors are simplified into this structure for display consistency.
		return new EngineError.ShaderCompilationError(
				message,
				"", // details are lost in parceling, but message is primary
				sourceLocations,
				null
		);
	}

	@Override
	public void writeToParcel(@NonNull Parcel dest, int flags) {
		dest.writeString(message);
		dest.writeTypedList(locations);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	private static class ParcelableSourceLocation implements Parcelable {
		public static final Creator<ParcelableSourceLocation> CREATOR = new Creator<>() {
			@NonNull
			@Contract("_ -> new")
			@Override
			public ParcelableSourceLocation createFromParcel(Parcel in) {
				return new ParcelableSourceLocation(in);
			}

			@NonNull
			@Contract(value = "_ -> new", pure = true)
			@Override
			public ParcelableSourceLocation[] newArray(int size) {
				return new ParcelableSourceLocation[size];
			}
		};
		private final int line;
		private final String message;

		ParcelableSourceLocation(@NonNull EngineError.SourceLocation location) {
			this.line = location.line();
			this.message = location.message();
		}

		protected ParcelableSourceLocation(@NonNull Parcel in) {
			line = in.readInt();
			message = in.readString();
		}

		@NonNull
		@Contract(" -> new")
		EngineError.SourceLocation toSourceLocation() {
			return new EngineError.SourceLocation(line, message);
		}

		@Override
		public void writeToParcel(@NonNull Parcel dest, int flags) {
			dest.writeInt(line);
			dest.writeString(message);
		}

		@Override
		public int describeContents() {
			return 0;
		}
	}
}