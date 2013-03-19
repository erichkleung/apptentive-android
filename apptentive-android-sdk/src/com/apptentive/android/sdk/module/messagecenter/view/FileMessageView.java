/*
 * Copyright (c) 2013, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.module.messagecenter.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.apptentive.android.sdk.Apptentive;
import com.apptentive.android.sdk.Log;
import com.apptentive.android.sdk.R;
import com.apptentive.android.sdk.model.StoredFile;
import com.apptentive.android.sdk.model.FileMessage;
import com.apptentive.android.sdk.util.ImageUtil;
import com.apptentive.android.sdk.util.Util;

import java.io.FileInputStream;

/**
 * @author Sky Kelsey
 */
public class FileMessageView extends MessageView<FileMessage> {

	// Some limits to keep images from being bigger than their display area.
	private final static float MAX_IMAGE_SCREEN_PROPORTION_X = 0.5f;
	private final static float MAX_IMAGE_SCREEN_PROPORTION_Y = 0.6f;

	// Some absolute size limits to keep bitmap sizes down.
	private final static int MAX_IMAGE_DISPLAY_WIDTH = 800;
	private final static int MAX_IMAGE_DISPLAY_HEIGHT = 800;

	public FileMessageView(Context context, FileMessage message) {
		super(context, message);
	}

	protected void init(FileMessage message) {
		super.init(message);
		LayoutInflater inflater = LayoutInflater.from(context);
		FrameLayout bodyLayout = (FrameLayout) findViewById(R.id.apptentive_message_body);
		inflater.inflate(R.layout.apptentive_message_body_file, bodyLayout);
	}

	public void updateMessage(final FileMessage newMessage) {
		FileMessage oldMessage = message;
		super.updateMessage(newMessage);

		if(newMessage == null) {
			return;
		}
		StoredFile storedFile = newMessage.getStoredFile();
		if(storedFile == null || storedFile.getLocalFilePath() == null) {
			return;
		}

		StoredFile oldStoredFile = null;
		if(oldMessage != null) {
			oldStoredFile = oldMessage.getStoredFile();
		}

		boolean hasNoOldFilePath = oldMessage == null || oldStoredFile.getLocalFilePath() == null;
		boolean pathDiffers = oldMessage != null && !storedFile.getLocalFilePath().equals(oldStoredFile.getLocalFilePath());
		if (hasNoOldFilePath || pathDiffers) {
			// TODO: Figure out a way to group into classes by mime type (image, text, other).
			String mimeType = storedFile.getMimeType();

			if(mimeType == null) {
				Log.e("FileMessage mime type is null.");
				return;
			}

			ImageView imageView = (ImageView) findViewById(R.id.apptentive_file_message_image);
			if (mimeType.contains("image")) {
				FileInputStream fis = null;
				Bitmap imageBitmap = null;
				try {
					System.gc();
					fis = Apptentive.getAppContext().openFileInput(storedFile.getLocalFilePath());
					Point point = Util.getScreenSize(context);
					int maxImageWidth = (int)(MAX_IMAGE_SCREEN_PROPORTION_X * point.x);
					int maxImageHeight = (int)(MAX_IMAGE_SCREEN_PROPORTION_Y * point.x);
					maxImageWidth = maxImageWidth > MAX_IMAGE_DISPLAY_WIDTH ? MAX_IMAGE_DISPLAY_WIDTH : maxImageWidth;
					maxImageHeight = maxImageHeight > MAX_IMAGE_DISPLAY_HEIGHT ? MAX_IMAGE_DISPLAY_HEIGHT : maxImageHeight;
					imageBitmap = ImageUtil.createScaledBitmapFromStream(fis, maxImageWidth, maxImageHeight, null);
				} catch (Exception e) {
					Log.e("Error opening stored file.", e);
				} finally {
					Util.ensureClosed(fis);
				}
				imageView.setImageBitmap(imageBitmap);
				imageView.setVisibility(View.VISIBLE);
			} else {
				// TODO: We aren't creating other FileMessage types than image yet. This isn't tested.
				TextView textView = (TextView) findViewById(R.id.apptentive_file_message_text);
				textView.setVisibility(View.VISIBLE);
				if (mimeType.contains("text")) {
					// Set content
					// TODO: Populate this view with the file contents. Truncate to just a few hundred characters maybe?
				} else {
					textView.setText(newMessage.getMimeType());
				}
			}
		}
	}
}
