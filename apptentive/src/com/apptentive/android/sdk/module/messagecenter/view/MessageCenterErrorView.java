/*
 * Copyright (c) 2015, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.module.messagecenter.view;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.apptentive.android.sdk.R;
import com.apptentive.android.sdk.util.Constants;

/**
 * @author Sky Kelsey
 */
public class MessageCenterErrorView extends FrameLayout {
	private Activity activity;

	public MessageCenterErrorView(final Activity activity) {
		super(activity.getApplicationContext());
		this.activity = activity;

		LayoutInflater inflater = activity.getLayoutInflater();
		inflater.inflate(R.layout.apptentive_message_center_error, this);

		ImageButton back = (ImageButton) findViewById(R.id.back);
		back.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				MessageCenterErrorView.this.activity.finish();
			}
		});

		SharedPreferences prefs = getContext().getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
		boolean serverErrorLastAttempt = prefs.getBoolean(Constants.PREF_KEY_MESSAGE_CENTER_SERVER_ERROR_LAST_ATTEMPT, false);
		if (serverErrorLastAttempt) {
			((ImageView) findViewById(R.id.icon)).setImageResource(R.drawable.apptentive_icon_server_error);
			((TextView) findViewById(R.id.message)).setText(R.string.apptentive_message_center_server_error);
		} else {
			((ImageView) findViewById(R.id.icon)).setImageResource(R.drawable.apptentive_icon_no_connection);
			((TextView) findViewById(R.id.message)).setText(R.string.apptentive_message_center_no_connection);
		}

	}
}
