/*
 * Copyright (c) 2015, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.module.engagement;

import android.content.Context;
import android.content.Intent;
import com.apptentive.android.sdk.Log;
import com.apptentive.android.sdk.ViewActivity;
import com.apptentive.android.sdk.model.CodePointStore;
import com.apptentive.android.sdk.model.Event;
import com.apptentive.android.sdk.model.EventManager;
import com.apptentive.android.sdk.model.ExtendedData;
import com.apptentive.android.sdk.module.ActivityContent;
import com.apptentive.android.sdk.module.engagement.interaction.InteractionManager;
import com.apptentive.android.sdk.module.engagement.interaction.model.Interaction;
import com.apptentive.android.sdk.module.metric.MetricModule;

import java.util.Map;

/**
 * @author Sky Kelsey
 */
public class EngagementModule {

	public static synchronized boolean engageInternal(Context context, String eventName) {
		return engage(context, "com.apptentive", "app", null, eventName,  null, null, (ExtendedData[]) null);
	}

	public static synchronized boolean engageInternal(Context context, String eventName, String data) {
		return engage(context, "com.apptentive", "app", null, eventName,  data, null, (ExtendedData[]) null);
	}

	public static synchronized boolean engageInternal(Context context, Interaction interaction, String eventName) {
		return engage(context, "com.apptentive", interaction.getType().name(), interaction.getId(), eventName, null, null, (ExtendedData[]) null);
	}

	public static synchronized boolean engageInternal(Context context, Interaction interaction, String eventName, String data) {
		return engage(context, "com.apptentive", interaction.getType().name(), interaction.getId(), eventName, data, null, (ExtendedData[]) null);
	}

	public static synchronized boolean engage(Context context, String vendor, String interaction, String interactionId, String eventName, String data, Map<String, Object> customData, ExtendedData... extendedData) {
		try {
			String eventLabel = generateEventLabel(vendor, interaction, eventName);
			Log.d("engage(%s)", eventLabel);

			CodePointStore.storeCodePointForCurrentAppVersion(context.getApplicationContext(), eventLabel);
			EventManager.sendEvent(context.getApplicationContext(), new Event(eventLabel, interactionId, data, customData, extendedData));
			return doEngage(context, eventLabel);
		} catch (Exception e) {
			MetricModule.sendError(context.getApplicationContext(), e, null, null);
		}
		return false;
	}

	public static boolean doEngage(Context context, String eventLabel) {
		Interaction interaction = InteractionManager.getApplicableInteraction(context.getApplicationContext(), eventLabel);
		if (interaction != null) {
			CodePointStore.storeInteractionForCurrentAppVersion(context, interaction.getId());
			launchInteraction(context, interaction);
			return true;
		}
		Log.d("No interaction to show.");
		return false;
	}

	public static void launchInteraction(Context context, Interaction interaction) {
		if (interaction != null) {
			Log.i("Launching interaction: %s", interaction.getType().toString());
			Intent intent = new Intent();
			intent.setClass(context, ViewActivity.class);
			intent.putExtra(ActivityContent.KEY, ActivityContent.Type.INTERACTION.toString());
			intent.putExtra(Interaction.KEY_NAME, interaction.toString());
			context.startActivity(intent);
		}
	}

	public static boolean canShowInteraction(Context context, String vendor, String interaction, String eventName) {
		String eventLabel = generateEventLabel(vendor, interaction, eventName);
		return canShowInteraction(context, eventLabel);
	}

	private static boolean canShowInteraction(Context context, String eventLabel) {
		Interaction interaction = InteractionManager.getApplicableInteraction(context, eventLabel);
		return interaction != null;
	}


	public static String generateEventLabel(String vendor, String interaction, String eventName) {
		return String.format("%s#%s#%s", encodeEventLabelPart(vendor), encodeEventLabelPart(interaction), encodeEventLabelPart(eventName));
	}

	/**
	 * Used only for encoding event names. DO NOT modify this method.
	 */
	private static String encodeEventLabelPart(String input) {
		return input.replace("%", "%25").replace("/", "%2F").replace("#", "%23");
	}
}
