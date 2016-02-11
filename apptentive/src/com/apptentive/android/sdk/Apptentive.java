/*
 * Copyright (c) 2015, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.apptentive.android.sdk.comm.ApptentiveClient;
import com.apptentive.android.sdk.comm.ApptentiveHttpResponse;
import com.apptentive.android.sdk.model.*;
import com.apptentive.android.sdk.module.engagement.EngagementModule;
import com.apptentive.android.sdk.module.engagement.interaction.InteractionManager;
import com.apptentive.android.sdk.module.messagecenter.MessageManager;
import com.apptentive.android.sdk.module.messagecenter.MessagePollingWorker;
import com.apptentive.android.sdk.module.messagecenter.UnreadMessagesListener;
import com.apptentive.android.sdk.lifecycle.ActivityLifecycleManager;
import com.apptentive.android.sdk.module.messagecenter.model.CompoundMessage;
import com.apptentive.android.sdk.module.metric.MetricModule;
import com.apptentive.android.sdk.module.rating.IRatingProvider;
import com.apptentive.android.sdk.module.survey.OnSurveyFinishedListener;
import com.apptentive.android.sdk.storage.*;
import com.apptentive.android.sdk.util.Constants;
import com.apptentive.android.sdk.util.Util;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;

import java.io.InputStream;
import java.util.*;

/**
 * This class contains the complete API for accessing Apptentive features from within your app.
 *
 * @author Sky Kelsey
 */
public class Apptentive {

	private Apptentive() {
	}

	// ****************************************************************************************
	// DELEGATE METHODS
	// ****************************************************************************************

	/**
	 * A reference count of the number of Activities that are started. If the count drops to zero, then no Activities are currently running.
	 * Any threads or receivers that are spawned by Apptentive should exit or stop listening when runningActivities == 0.
	 */
	private static int runningActivities;

	/**
	 * Call this method from each of your Activities' onStart() methods. Must be called before using other Apptentive APIs
	 * methods
	 *
	 * @param activity The Activity from which this method is called.
	 */
	public static void onStart(Activity activity) {
		try {
			init(activity);
			ActivityLifecycleManager.activityStarted(activity);
			if (runningActivities == 0) {
				PayloadSendWorker.appWentToForeground(activity.getApplicationContext());
				MessagePollingWorker.appWentToForeground(activity.getApplicationContext());
			}
			runningActivities++;
			MessageManager.setCurrentForgroundActivity(activity);
		} catch (Exception e) {
			Log.w("Error starting Apptentive Activity.", e);
			MetricModule.sendError(activity.getApplicationContext(), e, null, null);
		}
	}

	/**
	 * Call this method from each of your Activities' onStop() methods.
	 *
	 * @param activity The Activity from which this method is called.
	 */
	public static void onStop(Activity activity) {
		try {
			ActivityLifecycleManager.activityStopped(activity);
			runningActivities--;
			if (runningActivities < 0) {
				Log.e("Incorrect number of running Activities encountered. Resetting to 0. Did you make sure to call Apptentive.onStart() and Apptentive.onStop() in all your Activities?");
				runningActivities = 0;
			}
			// If there are no running activities, wake the thread so it can stop immediately and gracefully.
			if (runningActivities == 0) {
				PayloadSendWorker.appWentToBackground();
				MessagePollingWorker.appWentToBackground();
			}
			MessageManager.setCurrentForgroundActivity(null);
		} catch (Exception e) {
			Log.w("Error stopping Apptentive Activity.", e);
			MetricModule.sendError(activity.getApplicationContext(), e, null, null);
		}
	}


	// ****************************************************************************************
	// GLOBAL DATA METHODS
	// ****************************************************************************************

	/**
	 * Sets the user's email address. This email address will be sent to the Apptentive server to allow out of app
	 * communication, and to help provide more context about this user. This email will be the definitive email address
	 * for this user, unless one is provided directly by the user through an Apptentive UI. Calls to this method are
	 * idempotent. Calls to this method will overwrite any previously entered email, so if you don't want to overwrite
	 * the email provided by the user, make sure to check the value with {@link #getPersonEmail(Context)} before you call this method.
	 *
	 * @param context The Context from which this method is called.
	 * @param email   The user's email address.
	 */
	public static void setPersonEmail(Context context, String email) {
		PersonManager.storePersonEmail(context, email);
	}

	/**
	 * Retrieves the user's email address. This address may be set via {@link #setPersonEmail(Context, String)},
	 * or by the user through Message Center.
	 *
	 * @param context The Context from which this method is called.
	 * @return The person's email if set, else null.
	 */
	public static String getPersonEmail(Context context) {
		return PersonManager.loadPersonEmail(context);
	}

	/**
	 * Sets the user's name. This name will be sent to the Apptentive server and displayed in conversations you have
	 * with this person. This name will be the definitive username for this user, unless one is provided directly by the
	 * user through an Apptentive UI. Calls to this method are idempotent. Calls to this method will overwrite any
	 * previously entered email, so if you don't want to overwrite the email provided by the user, make sure to check
	 * the value with {@link #getPersonName(Context)} before you call this method.
	 *
	 * @param context The context from which this method is called.
	 * @param name    The user's name.
	 */
	public static void setPersonName(Context context, String name) {
		PersonManager.storePersonName(context, name);
	}

	/**
	 * Retrieves the user's name. This name may be set via {@link #setPersonName(Context, String)},
	 * or by the user through Message Center.
	 *
	 * @param context The Context from which this method is called.
	 * @return The person's name if set, else null.
	 */
	public static String getPersonName(Context context) {
		return PersonManager.loadPersonName(context);
	}


	/**
	 * <p>Allows you to pass arbitrary string data to the server along with this device's info. This method will replace all
	 * custom device data that you have set for this app. Calls to this method are idempotent.</p>
	 * <p>To add a single piece of custom device data, use {@link #addCustomDeviceData}</p>
	 * <p>To remove a single piece of custom device data, use {@link #removeCustomDeviceData}</p>
	 *
	 * @param context          The context from which this method is called.
	 * @param customDeviceData A Map of key/value pairs to send to the server.
	 * @deprecated
	 */
	public static void setCustomDeviceData(Context context, Map<String, String> customDeviceData) {
		try {
			CustomData customData = new CustomData();
			for (String key : customDeviceData.keySet()) {
				customData.put(key, customDeviceData.get(key));
			}
			DeviceManager.storeCustomDeviceData(context, customData);
		} catch (JSONException e) {
			Log.w("Unable to set custom device data.", e);
		}
	}

	/**
	 * Add a custom data String to the Device. Custom data will be sent to the server, is displayed
	 * in the Conversation view, and can be used in Interaction targeting.  Calls to this method are
	 * idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to store the data under.
	 * @param value   A String value.
	 */
	public static void addCustomDeviceData(Context context, String key, String value) {
		if (value != null) {
			value = value.trim();
		}
		ApptentiveInternal.addCustomDeviceData(context, key, value);
	}

	/**
	 * Add a custom data Number to the Device. Custom data will be sent to the server, is displayed
	 * in the Conversation view, and can be used in Interaction targeting.  Calls to this method are
	 * idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to store the data under.
	 * @param value   A Number value.
	 */
	public static void addCustomDeviceData(Context context, String key, Number value) {
		ApptentiveInternal.addCustomDeviceData(context, key, value);
	}

	/**
	 * Add a custom data Boolean to the Device. Custom data will be sent to the server, is displayed
	 * in the Conversation view, and can be used in Interaction targeting.  Calls to this method are
	 * idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to store the data under.
	 * @param value   A Boolean value.
	 */
	public static void addCustomDeviceData(Context context, String key, Boolean value) {
		ApptentiveInternal.addCustomDeviceData(context, key, value);
	}

	private static void addCustomDeviceData(Context context, String key, Version version) {
		ApptentiveInternal.addCustomDeviceData(context, key, version);
	}

	private static void addCustomDeviceData(Context context, String key, DateTime dateTime) {
		ApptentiveInternal.addCustomDeviceData(context, key, dateTime);
	}

	/**
	 * Remove a piece of custom data from the device. Calls to this method are idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to remove.
	 */
	public static void removeCustomDeviceData(Context context, String key) {
		CustomData customData = DeviceManager.loadCustomDeviceData(context);
		if (customData != null) {
			customData.remove(key);
			DeviceManager.storeCustomDeviceData(context, customData);
		}
	}

	/**
	 * <p>Allows you to pass arbitrary string data to the server along with this person's info. This method will replace all
	 * custom person data that you have set for this app. Calls to this method are idempotent.</p>
	 * <p>To add a single piece of custom person data, use {@link #addCustomPersonData}</p>
	 * <p>To remove a single piece of custom person data, use {@link #removeCustomPersonData}</p>
	 *
	 * @param context          The context from which this method is called.
	 * @param customPersonData A Map of key/value pairs to send to the server.
	 * @deprecated
	 */
	public static void setCustomPersonData(Context context, Map<String, String> customPersonData) {
		Log.w("Setting custom person data: %s", customPersonData.toString());
		try {
			CustomData customData = new CustomData();
			for (String key : customPersonData.keySet()) {
				customData.put(key, customPersonData.get(key));
			}
			PersonManager.storeCustomPersonData(context, customData);
		} catch (JSONException e) {
			Log.e("Unable to set custom person data.", e);
		}
	}

	/**
	 * Add a custom data String to the Person. Custom data will be sent to the server, is displayed
	 * in the Conversation view, and can be used in Interaction targeting.  Calls to this method are
	 * idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to store the data under.
	 * @param value   A String value.
	 */
	public static void addCustomPersonData(Context context, String key, String value) {
		if (value != null) {
			value = value.trim();
		}
		ApptentiveInternal.addCustomPersonData(context, key, value);
	}

	/**
	 * Add a custom data Number to the Person. Custom data will be sent to the server, is displayed
	 * in the Conversation view, and can be used in Interaction targeting.  Calls to this method are
	 * idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to store the data under.
	 * @param value   A Number value.
	 */
	public static void addCustomPersonData(Context context, String key, Number value) {
		ApptentiveInternal.addCustomPersonData(context, key, value);
	}

	/**
	 * Add a custom data Boolean to the Person. Custom data will be sent to the server, is displayed
	 * in the Conversation view, and can be used in Interaction targeting.  Calls to this method are
	 * idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to store the data under.
	 * @param value   A Boolean value.
	 */
	public static void addCustomPersonData(Context context, String key, Boolean value) {
		ApptentiveInternal.addCustomPersonData(context, key, value);
	}

	private static void addCustomPersonData(Context context, String key, Version version) {
		ApptentiveInternal.addCustomPersonData(context, key, version);
	}

	private static void addCustomPersonData(Context context, String key, DateTime dateTime) {
		ApptentiveInternal.addCustomPersonData(context, key, dateTime);
	}

	/**
	 * Remove a piece of custom data from the Person. Calls to this method are idempotent.
	 *
	 * @param context The context from which this method is called.
	 * @param key     The key to remove.
	 */
	public static void removeCustomPersonData(Context context, String key) {
		CustomData customData = PersonManager.loadCustomPersonData(context);
		if (customData != null) {
			customData.remove(key);
			PersonManager.storeCustomPersonData(context, customData);
		}
	}


	// ****************************************************************************************
	// THIRD PARTY INTEGRATIONS
	// ****************************************************************************************

	private static final String INTEGRATION_APPTENTIVE_PUSH = "apptentive_push";
	private static final String INTEGRATION_PARSE = "parse";
	private static final String INTEGRATION_URBAN_AIRSHIP = "urban_airship";
	private static final String INTEGRATION_AWS_SNS = "aws_sns";

	private static final String INTEGRATION_PUSH_TOKEN = "token";

	private static void addIntegration(Context context, String integration, Map<String, String> config) {
		if (integration == null || config == null) {
			return;
		}
		CustomData integrationConfig = DeviceManager.loadIntegrationConfig(context);
		try {
			JSONObject configJson = null;
			if (!integrationConfig.isNull(integration)) {
				configJson = integrationConfig.getJSONObject(integration);
			} else {
				configJson = new JSONObject();
				integrationConfig.put(integration, configJson);
			}
			for (String key : config.keySet()) {
				configJson.put(key, config.get(key));
			}
			Log.d("Adding integration config: %s", config.toString());
			DeviceManager.storeIntegrationConfig(context, integrationConfig);
			syncDevice(context);
		} catch (JSONException e) {
			Log.e("Error adding integration: %s, %s", e, integration, config.toString());
		}
	}

	/**
	 * Call {@link #setPushNotificationIntegration(Context, int, String)} with this value to allow Apptentive to send pushes
	 * to this device without a third party push provider. Requires a valid GCM configuration.
	 */
	public static final int PUSH_PROVIDER_APPTENTIVE = 0;

	/**
	 * Call {@link #setPushNotificationIntegration(Context, int, String)} with this value to allow Apptentive to send pushes
	 * to this device through your existing Parse Push integration. Requires a valid Parse integration.
	 */
	public static final int PUSH_PROVIDER_PARSE = 1;

	/**
	 * Call {@link #setPushNotificationIntegration(Context, int, String)} with this value to allow Apptentive to send pushes
	 * to this device through your existing Urban Airship Push integration. Requires a valid Urban
	 * Airship Push integration.
	 */
	public static final int PUSH_PROVIDER_URBAN_AIRSHIP = 2;

	/**
	 * Call {@link #setPushNotificationIntegration(Context, int, String)} with this value to allow Apptentive to send pushes
	 * to this device through your existing Amazon AWS SNS integration. Requires a valid Amazon AWS SNS
	 * integration.
	 */
	public static final int PUSH_PROVIDER_AMAZON_AWS_SNS = 3;

	/**
	 * Sends push provider information to our server to allow us to send pushes to this device when
	 * you reply to your customers. Only one push provider is allowed to be active at a time, so you
	 * should only call this method once. Please see our
	 * <a href="http://www.apptentive.com/docs/android/integration/#push-notifications">integration guide</a> for
	 * instructions.
	 *
	 * @param context      The Context from which this method is called.
	 * @param pushProvider One of the following:
	 *                     <ul>
	 *                     <li>{@link #PUSH_PROVIDER_APPTENTIVE}</li>
	 *                     <li>{@link #PUSH_PROVIDER_PARSE}</li>
	 *                     <li>{@link #PUSH_PROVIDER_URBAN_AIRSHIP}</li>
	 *                     <li>{@link #PUSH_PROVIDER_AMAZON_AWS_SNS}</li>
	 *                     </ul>
	 * @param token        The push provider token you receive from your push provider. The format is push provider specific.
	 *                     <dl>
	 *                     <dt>Apptentive</dt>
	 *                     <dd>If you are using Apptentive to send pushes directly to GCM, pass in the GCM Registration ID, which you can
	 *                     <a href="https://github.com/googlesamples/google-services/blob/73f8a4fcfc93da08a40b96df3537bb9b6ef1b0fa/android/gcm/app/src/main/java/gcm/play/android/samples/com/gcmquickstart/RegistrationIntentService.java#L51">access like this</a>.
	 *                     </dd>
	 *                     <dt>Parse</dt>
	 *                     <dd>The Parse <a href="https://parse.com/docs/android/guide#push-notifications">deviceToken</a></dd>
	 *                     <dt>Urban Airship</dt>
	 *                     <dd>The Urban Airship Channel ID, which you can
	 *                     <a href="https://github.com/urbanairship/android-samples/blob/8ad77e5e81a1b0507c6a2c45a5c30a1e2da851e9/PushSample/src/com/urbanairship/push/sample/IntentReceiver.java#L43">access like this</a>.
	 *                     </dd>
	 *                     <dt>Amazon AWS SNS</dt>
	 *                     <dd>The GCM Registration ID, which you can <a href="http://docs.aws.amazon.com/sns/latest/dg/mobile-push-gcm.html#registration-id-gcm">access like this</a>.</dd>
	 *                     </dl>
	 */
	public static void setPushNotificationIntegration(Context context, int pushProvider, String token) {
		try {
			CustomData integrationConfig = getIntegrationConfigurationWithoutPushProviders(context);
			JSONObject pushObject = new JSONObject();
			pushObject.put(INTEGRATION_PUSH_TOKEN, token);
			switch (pushProvider) {
				case PUSH_PROVIDER_APPTENTIVE:
					integrationConfig.put(INTEGRATION_APPTENTIVE_PUSH, pushObject);
					break;
				case PUSH_PROVIDER_PARSE:
					integrationConfig.put(INTEGRATION_PARSE, pushObject);
					break;
				case PUSH_PROVIDER_URBAN_AIRSHIP:
					integrationConfig.put(INTEGRATION_URBAN_AIRSHIP, pushObject);
					break;
				case PUSH_PROVIDER_AMAZON_AWS_SNS:
					integrationConfig.put(INTEGRATION_AWS_SNS, pushObject);
					break;
				default:
					Log.e("Invalid pushProvider: %d", pushProvider);
					return;
			}
			DeviceManager.storeIntegrationConfig(context, integrationConfig);
			syncDevice(context);
		} catch (JSONException e) {
			Log.e("Error setting push integration.", e);
			return;
		}
	}

	private static CustomData getIntegrationConfigurationWithoutPushProviders(Context context) {
		CustomData integrationConfig = DeviceManager.loadIntegrationConfig(context);
		if (integrationConfig != null) {
			integrationConfig.remove(INTEGRATION_APPTENTIVE_PUSH);
			integrationConfig.remove(INTEGRATION_PARSE);
			integrationConfig.remove(INTEGRATION_URBAN_AIRSHIP);
			integrationConfig.remove(INTEGRATION_AWS_SNS);
		}
		return integrationConfig;
	}

	// ****************************************************************************************
	// PUSH NOTIFICATIONS
	// ****************************************************************************************

	/**
	 * Determines whether this Intent is a push notification sent from Apptentive.
	 *
	 * @param intent The opened push notification Intent you received in your BroadcastReceiver.
	 * @return True if the Intent contains Apptentive push information.
	 */
	public static boolean isApptentivePushNotification(Intent intent) {
		return ApptentiveInternal.getApptentivePushNotificationData(intent) != null;
	}

	/**
	 * Determines whether this Bundle came from an Apptentive push notification. This method is used with Urban Airship
	 * integrations.
	 *
	 * @param bundle The Extra data from an opened push notification.
	 * @return True if the Intent contains Apptentive push information.
	 */
	public static boolean isApptentivePushNotification(Bundle bundle) {
		return ApptentiveInternal.getApptentivePushNotificationData(bundle) != null;
	}

	/**
	 * <p>Saves Apptentive specific data from a push notification Intent. In your BroadcastReceiver, if the push notification
	 * came from Apptentive, it will have data that needs to be saved before you launch your Activity. You must call this
	 * method <strong>every time</strong> you get a push opened Intent, and before you launch your Activity. If the push
	 * notification did not come from Apptentive, this method has no effect.</p>
	 * <p>Use this method when using Parse and Amazon SNS as push providers.</p>
	 *
	 * @param context The Context from which this method is called.
	 * @param intent  The Intent that you received when the user opened a push notification.
	 * @return true if the push data came from Apptentive.
	 */
	public static boolean setPendingPushNotification(Context context, Intent intent) {
		String apptentive = ApptentiveInternal.getApptentivePushNotificationData(intent);
		if (apptentive != null) {
			return ApptentiveInternal.setPendingPushNotification(context, apptentive);
		}
		return false;
	}

	/**
	 * Saves off the data contained in a push notification sent to this device from Apptentive. Use
	 * this method when a push notification is opened, and you only have access to a push data
	 * Bundle containing an "apptentive" key. This will generally be used with direct Apptentive Push
	 * notifications, or when using Urban Airship as a push provider. Calling this method for a push
	 * that did not come from Apptentive has no effect.
	 *
	 * @param context The context from which this method was called.
	 * @param data    A Bundle containing the GCM data object from the push notification.
	 * @return true if the push data came from Apptentive.
	 */
	public static boolean setPendingPushNotification(Context context, Bundle data) {
		String apptentive = ApptentiveInternal.getApptentivePushNotificationData(data);
		if (apptentive != null) {
			return ApptentiveInternal.setPendingPushNotification(context, apptentive);
		}
		return false;
	}

	/**
	 * Launches Apptentive features based on a push notification Intent. Before you call this, you
	 * must call {@link #setPendingPushNotification(Context, Intent)} or
	 * {@link #setPendingPushNotification(Context, Bundle)} in your Broadcast receiver when
	 * a push notification is opened by the user. This method must be called from the Activity that
	 * you launched from the BroadcastReceiver. This method will only handle Apptentive originated
	 * push notifications, so you can and should call it any time your push notification launches an
	 * Activity.
	 *
	 * @param activity The Activity from which this method is called.
	 * @return True if a call to this method resulted in Apptentive displaying a View.
	 */
	public static boolean handleOpenedPushNotification(Activity activity) {
		SharedPreferences prefs = activity.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
		String pushData = prefs.getString(Constants.PREF_KEY_PENDING_PUSH_NOTIFICATION, null);
		prefs.edit().remove(Constants.PREF_KEY_PENDING_PUSH_NOTIFICATION).apply(); // Remove our data so this won't run twice.
		if (pushData != null) {
			Log.i("Handling opened Apptentive push notification.");
			try {
				JSONObject pushJson = new JSONObject(pushData);
				ApptentiveInternal.PushAction action = ApptentiveInternal.PushAction.unknown;
				if (pushJson.has(ApptentiveInternal.PUSH_ACTION)) {
					action = ApptentiveInternal.PushAction.parse(pushJson.getString(ApptentiveInternal.PUSH_ACTION));
				}
				switch (action) {
					case pmc:
						Apptentive.showMessageCenter(activity);
						return true;
					default:
						Log.v("Unknown Apptentive push notification action: \"%s\"", action.name());
				}
			} catch (JSONException e) {
				Log.w("Error parsing JSON from push notification.", e);
				MetricModule.sendError(activity.getApplicationContext(), e, "Parsing Push notification", pushData);
			}
		}
		return false;
	}


	// ****************************************************************************************
	// RATINGS
	// ****************************************************************************************

	/**
	 * Use this to choose where to send the user when they are prompted to rate the app. This should be the same place
	 * that the app was downloaded from.
	 *
	 * @param ratingProvider A {@link IRatingProvider} value.
	 */

	public static void setRatingProvider(IRatingProvider ratingProvider) {
		ApptentiveInternal.setRatingProvider(ratingProvider);
	}

	/**
	 * If there are any properties that your {@link IRatingProvider} implementation requires, populate them here. This
	 * is not currently needed with the Google Play and Amazon Appstore IRatingProviders.
	 *
	 * @param key   A String
	 * @param value A String
	 */
	public static void putRatingProviderArg(String key, String value) {
		ApptentiveInternal.putRatingProviderArg(key, value);
	}

	// ****************************************************************************************
	// MESSAGE CENTER
	// ****************************************************************************************


	/**
	 * Opens the Apptentive Message Center UI Activity
	 *
	 * @param activity The Activity from which to launch the Message Center
	 * @return true if Message Center was shown, else false.
	 */
	public static boolean showMessageCenter(Activity activity) {
		return showMessageCenter(activity, null);
	}

	/**
	 * Opens the Apptentive Message Center UI Activity, and allows custom data to be sent with the next message the user
	 * sends. If the user sends multiple messages, this data will only be sent with the first message sent after this
	 * method is invoked. Additional invocations of this method with custom data will repeat this process.
	 *
	 * @param activity   The Activity from which to launch the Message Center
	 * @param customData A Map of String keys to Object values. Objects may be Strings, Numbers, or Booleans.
	 *                   If any message is sent by the Person, this data is sent with it, and then
	 *                   cleared. If no message is sent, this data is discarded.
	 * @return true if Message Center was shown, else false.
	 */
	public static boolean showMessageCenter(Activity activity, Map<String, Object> customData) {
		try {
			return ApptentiveInternal.showMessageCenterInternal(activity, customData);
		} catch (Exception e) {
			Log.w("Error starting Apptentive Activity.", e);
			MetricModule.sendError(activity.getApplicationContext(), e, null, null);
		}
		return false;
	}

	/**
	 * Our SDK must connect to our server at least once to download initial configuration for Message
	 * Center. Call this method to see whether or not Message Center can be displayed.
	 *
	 * @param context The context from which this method is called.
	 * @return true if a call to {@link #showMessageCenter(Activity)} will display Message Center, else false.
	 */
	public static boolean canShowMessageCenter(Context context) {
		return ApptentiveInternal.canShowMessageCenterInternal(context);
	}

	/**
	 * Set a listener to be notified when the number of unread messages in the Message Center changes.
	 *
	 * @param listener An UnreadMessageListener that you instantiate.
	 * @deprecated use {@link #addUnreadMessagesListener(UnreadMessagesListener)} instead.
	 */
	@Deprecated
	public static void setUnreadMessagesListener(UnreadMessagesListener listener) {
		MessageManager.setHostUnreadMessagesListener(listener);
	}

	/**
	 * Add a listener to be notified when the number of unread messages in the Message Center changes.
	 *
	 * @param listener An UnreadMessageListener that you instantiate. Do not pass in an anonymous class.
	 *                 Instead, create your listener as an instance variable and pass that in. This
	 *                 allows us to keep a weak reference to avoid memory leaks.
	 */
	public static void addUnreadMessagesListener(UnreadMessagesListener listener) {
		MessageManager.addHostUnreadMessagesListener(listener);
	}

	/**
	 * Returns the number of unread messages in the Message Center.
	 *
	 * @param context The Context from which this method is called.
	 * @return The number of unread messages.
	 */
	public static int getUnreadMessageCount(Context context) {
		try {
			return MessageManager.getUnreadMessageCount(context);
		} catch (Exception e) {
			MetricModule.sendError(context.getApplicationContext(), e, null, null);
		}
		return 0;
	}

	/**
	 * Sends a text message to the server. This message will be visible in the conversation view on the server, but will
	 * not be shown in the client's Message Center.
	 *
	 * @param context The Context from which this method is called.
	 * @param text    The message you wish to send.
	 */
	public static void sendAttachmentText(Context context, String text) {
		try {
			CompoundMessage message = new CompoundMessage();
			message.setBody(text);
			message.setRead(true);
			message.setHidden(true);
			message.setSenderId(GlobalInfo.getPersonId(context.getApplicationContext()));
			message.setAssociatedFiles(context, null);
			MessageManager.sendMessage(context.getApplicationContext(), message);
		} catch (Exception e) {
			Log.w("Error sending attachment text.", e);
			MetricModule.sendError(context, e, null, null);
		}
	}

	/**
	 * Sends a file to the server. This file will be visible in the conversation view on the server, but will not be shown
	 * in the client's Message Center. A local copy of this file will be made until the message is transmitted, at which
	 * point the temporary file will be deleted.
	 *
	 * @param context The Context from which this method was called.
	 * @param uri     The URI of the local resource file.
	 */
	public static void sendAttachmentFile(Context context, String uri) {
		try {
			if (TextUtils.isEmpty(uri)) {
				return;
			}

			CompoundMessage message = new CompoundMessage();
			// No body, just attachment
			message.setBody(null);
			message.setRead(true);
			message.setHidden(true);
			message.setSenderId(GlobalInfo.getPersonId(context.getApplicationContext()));

			ArrayList<StoredFile> attachmentStoredFiles = new ArrayList<StoredFile>();
			/* Make a local copy in the cache dir. By default the file name is "apptentive-api-file + nonce"
			 * If original uri is known, the name will be taken from the original uri
			 */
			String localFilePath = Util.generateCacheFilePathFromNonceOrPrefix(context, message.getNonce(), Uri.parse(uri).getLastPathSegment());

			String mimeType = Util.getMimeTypeFromUri(context, Uri.parse(uri));
			MimeTypeMap mime = MimeTypeMap.getSingleton();
			String extension = mime.getExtensionFromMimeType(mimeType);

			// If we can't get the mime type from the uri, try getting it from the extension.
			if (extension == null) {
				extension = MimeTypeMap.getFileExtensionFromUrl(uri);
			}
			if (mimeType == null && extension != null) {
				mimeType = mime.getMimeTypeFromExtension(extension);
			}
			if (!TextUtils.isEmpty(extension)) {
				localFilePath += "." + extension;
			}
			StoredFile storedFile = Util.createLocalStoredFile(context, uri, localFilePath, mimeType);
			if (storedFile == null) {
				return;
			}

			storedFile.setId(message.getNonce());
			attachmentStoredFiles.add(storedFile);

			message.setAssociatedFiles(context, attachmentStoredFiles);
			MessageManager.sendMessage(context.getApplicationContext(), message);

		} catch (Exception e) {
			Log.w("Error sending attachment file.", e);
			MetricModule.sendError(context, e, null, null);
		}
	}

	/**
	 * Sends a file to the server. This file will be visible in the conversation view on the server, but will not be shown
	 * in the client's Message Center. A local copy of this file will be made until the message is transmitted, at which
	 * point the temporary file will be deleted.
	 *
	 * @param context  The Context from which this method was called.
	 * @param content  A byte array of the file contents.
	 * @param mimeType The mime type of the file.
	 */
	public static void sendAttachmentFile(Context context, byte[] content, String mimeType) {
		ByteArrayInputStream is = null;
		try {
			is = new ByteArrayInputStream(content);
			sendAttachmentFile(context, is, mimeType);
		} finally {
			Util.ensureClosed(is);
		}
	}

	/**
	 * Sends a file to the server. This file will be visible in the conversation view on the server, but will not be shown
	 * in the client's Message Center. A local copy of this file will be made until the message is transmitted, at which
	 * point the temporary file will be deleted.
	 *
	 * @param context  The Context from which this method was called.
	 * @param is       An InputStream from the desired file.
	 * @param mimeType The mime type of the file.
	 */
	public static void sendAttachmentFile(Context context, InputStream is, String mimeType) {
		try {
			if (is == null) {
				return;
			}

			CompoundMessage message = new CompoundMessage();
			// No body, just attachment
			message.setBody(null);
			message.setRead(true);
			message.setHidden(true);
			message.setSenderId(GlobalInfo.getPersonId(context.getApplicationContext()));

			ArrayList<StoredFile> attachmentStoredFiles = new ArrayList<StoredFile>();
			String localFilePath = Util.generateCacheFilePathFromNonceOrPrefix(context, message.getNonce(), null);

			String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
			if (!TextUtils.isEmpty(extension)) {
				localFilePath += "." + extension;
			}
			// When created from InputStream, there is no source file uri or path, thus just use the cache file path
			StoredFile storedFile = Util.createLocalStoredFile(is, localFilePath, localFilePath, mimeType);
			if (storedFile == null) {
				return;
			}
			storedFile.setId(message.getNonce());
			attachmentStoredFiles.add(storedFile);

			message.setAssociatedFiles(context, attachmentStoredFiles);
			MessageManager.sendMessage(context.getApplicationContext(), message);

		} catch (Exception e) {
			Log.w("Error sending attachment file.", e);
			MetricModule.sendError(context, e, null, null);
		}
	}

	/**
	 * This method takes a unique event string, stores a record of that event having been visited, figures out
	 * if there is an interaction that is able to run for this event, and then runs it. If more than one interaction
	 * can run, then the most appropriate interaction takes precedence. Only one interaction at most will run per
	 * invocation of this method.
	 *
	 * @param context  The Context from which this method is called.
	 * @param event    A unique String representing the line this method is called on. For instance, you may want to have
	 *                 the ability to target interactions to run after the user uploads a file in your app. You may then
	 *                 call <strong><code>engage(activity, "finished_upload");</code></strong>
	 * @return true if the an interaction was shown, else false.
	 */
	public static synchronized boolean engage(Context context, String event) {
		return EngagementModule.engage(context, "local", "app", null, event, null, null, (ExtendedData[]) null);
	}

	/**
	 * This method takes a unique event string, stores a record of that event having been visited, figures out
	 * if there is an interaction that is able to run for this event, and then runs it. If more than one interaction
	 * can run, then the most appropriate interaction takes precedence. Only one interaction at most will run per
	 * invocation of this method.
	 *
	 * @param context    The Context from which this method is called.
	 * @param event      A unique String representing the line this method is called on. For instance, you may want to have
	 *                   the ability to target interactions to run after the user uploads a file in your app. You may then
	 *                   call <strong><code>engage(context, "finished_upload");</code></strong>
	 * @param customData A Map of String keys to Object values. Objects may be Strings, Numbers, or Booleans. This data
	 *                   is sent to the server for tracking information in the context of the engaged Event.
	 * @return true if the an interaction was shown, else false.
	 */
	public static synchronized boolean engage(Context context, String event, Map<String, Object> customData) {
		return EngagementModule.engage(context, "local", "app", null, event, null, customData, (ExtendedData[]) null);
	}

	/**
	 * This method takes a unique event string, stores a record of that event having been visited, figures out
	 * if there is an interaction that is able to run for this event, and then runs it. If more than one interaction
	 * can run, then the most appropriate interaction takes precedence. Only one interaction at most will run per
	 * invocation of this method.
	 *
	 * @param context      The Context from which this method is called.
	 * @param event        A unique String representing the line this method is called on. For instance, you may want to have
	 *                     the ability to target interactions to run after the user uploads a file in your app. You may then
	 *                     call <strong><code>engage(activity, "finished_upload");</code></strong>
	 * @param customData   A Map of String keys to Object values. Objects may be Strings, Numbers, or Booleans. This data
	 *                     is sent to the server for tracking information in the context of the engaged Event.
	 * @param extendedData An array of ExtendedData objects. ExtendedData objects used to send structured data that has
	 *                     specific meaning to the server. By using an {@link ExtendedData} object instead of arbitrary
	 *                     customData, special meaning can be derived. Supported objects include {@link TimeExtendedData},
	 *                     {@link LocationExtendedData}, and {@link CommerceExtendedData}. Include each type only once.
	 * @return true if the an interaction was shown, else false.
	 */
	public static synchronized boolean engage(Context context, String event, Map<String, Object> customData, ExtendedData... extendedData) {
		return EngagementModule.engage(context, "local", "app", null, event, null, customData, extendedData);
	}

	/**
	 * @param context The Context from which this method is called.
	 * @param event   A unique String representing the line this method is called on. For instance, you may want to have
	 *                the ability to target interactions to run after the user uploads a file in your app. You may then
	 *                call <strong><code>engage(activity, "finished_upload");</code></strong>
	 * @return true if an immediate call to engage() with the same event name would result in an Interaction being displayed, otherwise false.
	 * @deprecated Use {@link #canShowInteraction(Context, String)}() instead. The behavior is identical. Only the name has changed.
	 */
	public static synchronized boolean willShowInteraction(Context context, String event) {
		return canShowInteraction(context, event);
	}

	/**
	 * This method can be used to determine if a call to one of the <strong><code>engage()</code></strong> methods such as
	 * {@link #engage(Context, String)} using the same event name will
	 * result in the display of an  Interaction. This is useful if you need to know whether an Interaction will be
	 * displayed before you create a UI Button, etc.
	 *
	 * @param context The Context from which this method is called.
	 * @param event   A unique String representing the line this method is called on. For instance, you may want to have
	 *                the ability to target interactions to run after the user uploads a file in your app. You may then
	 *                call <strong><code>engage(activity, "finished_upload");</code></strong>
	 * @return true if an immediate call to engage() with the same event name would result in an Interaction being displayed, otherwise false.
	 */
	public static synchronized boolean canShowInteraction(Context context, String event) {
		try {
			return EngagementModule.canShowInteraction(context, "local", "app", event);
		} catch (Exception e) {
			MetricModule.sendError(context, e, null, null);
		}
		return false;
	}

	/**
	 * Pass in a listener. The listener will be called whenever a survey is finished.
	 *
	 * @param listener The {@link com.apptentive.android.sdk.module.survey.OnSurveyFinishedListener} listener to call when the survey is finished.
	 */
	public static void setOnSurveyFinishedListener(OnSurveyFinishedListener listener) {
		ApptentiveInternal.setOnSurveyFinishedListener(listener);
	}

	// ****************************************************************************************
	// INTERNAL METHODS
	// ****************************************************************************************

	private static void init(Activity activity) {

		//
		// First, initialize data relies on synchronous reads from local resources.
		//

		final Context appContext = activity.getApplicationContext();

		if (!GlobalInfo.initialized) {
			SharedPreferences prefs = appContext.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);

			// First, Get the api key, and figure out if app is debuggable.
			GlobalInfo.isAppDebuggable = false;
			String apiKey = prefs.getString(Constants.PREF_KEY_API_KEY, null);
			boolean apptentiveDebug = false;
			String logLevelOverride = null;
			try {
				ApplicationInfo ai = appContext.getPackageManager().getApplicationInfo(appContext.getPackageName(), PackageManager.GET_META_DATA);
				Bundle metaData = ai.metaData;
				if (metaData != null) {
					if (apiKey == null) {
						apiKey = metaData.getString(Constants.MANIFEST_KEY_APPTENTIVE_API_KEY);
						Log.d("Saving API key for the first time: %s", apiKey);
						prefs.edit().putString(Constants.PREF_KEY_API_KEY, apiKey).apply();
					} else {
						Log.d("Using cached API Key: %s", apiKey);
					}
					logLevelOverride = metaData.getString(Constants.MANIFEST_KEY_APPTENTIVE_LOG_LEVEL);
					apptentiveDebug = metaData.getBoolean(Constants.MANIFEST_KEY_APPTENTIVE_DEBUG);
					ApptentiveClient.useStagingServer = metaData.getBoolean(Constants.MANIFEST_KEY_USE_STAGING_SERVER);
				}
				if (apptentiveDebug) {
					Log.i("Apptentive debug logging set to VERBOSE.");
					ApptentiveInternal.setMinimumLogLevel(Log.Level.VERBOSE);
				} else if (logLevelOverride != null) {
					Log.i("Overriding log level: %s", logLevelOverride);
					ApptentiveInternal.setMinimumLogLevel(Log.Level.parse(logLevelOverride));
				} else {
					GlobalInfo.isAppDebuggable = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
					if (GlobalInfo.isAppDebuggable) {
						ApptentiveInternal.setMinimumLogLevel(Log.Level.VERBOSE);
					}
				}
			} catch (Exception e) {
				Log.e("Unexpected error while reading application info.", e);
			}

			Log.i("Debug mode enabled? %b", GlobalInfo.isAppDebuggable);

			// If we are in debug mode, but no api key is found, throw an exception. Otherwise, just assert log. We don't want to crash a production app.
			String errorString = "No Apptentive api key specified. Please make sure you have specified your api key in your AndroidManifest.xml";
			if ((Util.isEmpty(apiKey))) {
				if (GlobalInfo.isAppDebuggable) {
					AlertDialog alertDialog = new AlertDialog.Builder(activity)
							.setTitle("Error")
							.setMessage(errorString)
							.setPositiveButton("OK", null)
							.create();
					alertDialog.setCanceledOnTouchOutside(false);
					alertDialog.show();
				}
				Log.e(errorString);
			}
			GlobalInfo.apiKey = apiKey;

			Log.i("API Key: %s", GlobalInfo.apiKey);

			// Grab app info we need to access later on.
			GlobalInfo.appPackage = appContext.getPackageName();
			GlobalInfo.androidId = Settings.Secure.getString(appContext.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

			// Check the host app version, and notify modules if it's changed.
			try {
				PackageManager packageManager = appContext.getPackageManager();
				PackageInfo packageInfo = packageManager.getPackageInfo(appContext.getPackageName(), 0);

				Integer currentVersionCode = packageInfo.versionCode;
				String currentVersionName = packageInfo.versionName;
				VersionHistoryStore.VersionHistoryEntry lastVersionEntrySeen = VersionHistoryStore.getLastVersionSeen(appContext);
				if (lastVersionEntrySeen == null) {
					onVersionChanged(appContext, null, currentVersionCode, null, currentVersionName);
				} else {
					if (!currentVersionCode.equals(lastVersionEntrySeen.versionCode) || !currentVersionName.equals(lastVersionEntrySeen.versionName)) {
						onVersionChanged(appContext, lastVersionEntrySeen.versionCode, currentVersionCode, lastVersionEntrySeen.versionName, currentVersionName);
					}
				}

				GlobalInfo.appDisplayName = packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageInfo.packageName, 0)).toString();
			} catch (PackageManager.NameNotFoundException e) {
				// Nothing we can do then.
				GlobalInfo.appDisplayName = "this app";
			}

			String lastSeenSdkVersion = prefs.getString(Constants.PREF_KEY_LAST_SEEN_SDK_VERSION, "");
			if (!lastSeenSdkVersion.equals(Constants.APPTENTIVE_SDK_VERSION)) {
				onSdkVersionChanged(appContext, lastSeenSdkVersion, Constants.APPTENTIVE_SDK_VERSION);
			}

			GlobalInfo.initialized = true;
			Log.v("Done initializing...");
		} else {
			Log.v("Already initialized...");
		}

		// Initialize the Conversation Token, or fetch if needed. Fetch config it the token is available.
		if (GlobalInfo.getConversationToken(appContext)  == null || GlobalInfo.getPersonId(appContext) == null) {
			asyncFetchConversationToken(appContext);
		} else {
			asyncFetchAppConfiguration(appContext);
			InteractionManager.asyncFetchAndStoreInteractions(appContext);
		}

		// TODO: Do this on a dedicated thread if it takes too long. Some devices are slow to read device data.
		syncDevice(appContext);
		syncSdk(appContext);
		syncPerson(appContext);

		Log.d("Default Locale: %s", Locale.getDefault().toString());
		Log.d("Conversation id: %s", appContext.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).getString(Constants.PREF_KEY_CONVERSATION_ID, "null"));
	}

	private static void onVersionChanged(Context context, Integer previousVersionCode, Integer currentVersionCode, String previousVersionName, String currentVersionName) {
		Log.i("Version changed: Name: %s => %s, Code: %d => %d", previousVersionName, currentVersionName, previousVersionCode, currentVersionCode);
		VersionHistoryStore.updateVersionHistory(context, currentVersionCode, currentVersionName);
		AppRelease appRelease = AppReleaseManager.storeAppReleaseAndReturnDiff(context);
		if (appRelease != null) {
			Log.d("App release was updated.");
			ApptentiveDatabase.getInstance(context).addPayload(appRelease);
		}
		invalidateCaches(context);
	}

	private static void onSdkVersionChanged(Context context, String previousSdkVersion, String currentSdkVersion) {
		Log.i("Sdk version changed: %s => %s", previousSdkVersion, currentSdkVersion);
		context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).edit().putString(Constants.PREF_KEY_LAST_SEEN_SDK_VERSION, currentSdkVersion).apply();
		invalidateCaches(context);
	}

	/**
	 * We want to make sure the app is using the latest configuration from the server if the app or sdk version changes.
	 *
	 * @param context
	 */
	private static void invalidateCaches(Context context) {
		InteractionManager.updateCacheExpiration(context, 0);
		Configuration config = Configuration.load(context);
		config.setConfigurationCacheExpirationMillis(System.currentTimeMillis());
		config.save(context);
	}

	private synchronized static void asyncFetchConversationToken(final Context context) {
		Thread thread = new Thread() {
			@Override
			public void run() {
				fetchConversationToken(context);
			}
		};
		Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable throwable) {
				Log.w("Caught UncaughtException in thread \"%s\"", throwable, thread.getName());
				MetricModule.sendError(context.getApplicationContext(), throwable, null, null);
			}
		};
		thread.setUncaughtExceptionHandler(handler);
		thread.setName("Apptentive-FetchConversationToken");
		thread.start();
	}

	/**
	 * First looks to see if we've saved the ConversationToken in memory, then in SharedPreferences, and finally tries to get one
	 * from the server.
	 */
	private static void fetchConversationToken(Context context) {
		// Try to fetch a new one from the server.
		ConversationTokenRequest request = new ConversationTokenRequest();

		// Send the Device and Sdk now, so they are available on the server from the start.
		request.setDevice(DeviceManager.storeDeviceAndReturnIt(context));
		request.setSdk(SdkManager.storeSdkAndReturnIt(context));
		request.setPerson(PersonManager.storePersonAndReturnIt(context));

		// TODO: Allow host app to send a user id, if available.
		ApptentiveHttpResponse response = ApptentiveClient.getConversationToken(context, request);
		if (response == null) {
			Log.w("Got null response fetching ConversationToken.");
			return;
		}
		if (response.isSuccessful()) {
			try {
				JSONObject root = new JSONObject(response.getContent());
				String conversationToken = root.getString("token");
				Log.d("ConversationToken: " + conversationToken);
				String conversationId = root.getString("id");
				Log.d("New Conversation id: %s", conversationId);

				if (conversationToken != null && !conversationToken.equals("")) {
					GlobalInfo.setConversationToken(context, conversationToken);
					GlobalInfo.setConversationId(context, conversationId);
				}
				String personId = root.getString("person_id");
				Log.d("PersonId: " + personId);
				if (personId != null && !personId.equals("")) {
					GlobalInfo.setPersonId(context, personId);
				}
				// Try to fetch app configuration, since it depends on the conversation token.
				asyncFetchAppConfiguration(context);
				InteractionManager.asyncFetchAndStoreInteractions(context);
			} catch (JSONException e) {
				Log.e("Error parsing ConversationToken response json.", e);
			}
		}
	}

	/**
	 * Fetches the global app configuration from the server and stores the keys into our SharedPreferences.
	 */
	private static void fetchAppConfiguration(Context context) {
		boolean force = GlobalInfo.isAppDebuggable;

		// Don't get the app configuration unless forced, or the cache has expired.
		if (force || Configuration.load(context).hasConfigurationCacheExpired()) {
			Log.i("Fetching new Configuration.");
			ApptentiveHttpResponse response = ApptentiveClient.getAppConfiguration(context);
			try {
				Map<String, String> headers = response.getHeaders();
				if (headers != null) {
					String cacheControl = headers.get("Cache-Control");
					Integer cacheSeconds = Util.parseCacheControlHeader(cacheControl);
					if (cacheSeconds == null) {
						cacheSeconds = Constants.CONFIG_DEFAULT_APP_CONFIG_EXPIRATION_DURATION_SECONDS;
					}
					Log.d("Caching configuration for %d seconds.", cacheSeconds);
					Configuration config = new Configuration(response.getContent());
					config.setConfigurationCacheExpirationMillis(System.currentTimeMillis() + cacheSeconds * 1000);
					config.save(context);
				}
			} catch (JSONException e) {
				Log.e("Error parsing app configuration from server.", e);
			}
		} else {
			Log.v("Using cached Configuration.");
		}
	}

	private static void asyncFetchAppConfiguration(final Context context) {
		Thread thread = new Thread() {
			public void run() {
				fetchAppConfiguration(context);
			}
		};
		Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable throwable) {
				Log.e("Caught UncaughtException in thread \"%s\"", throwable, thread.getName());
				MetricModule.sendError(context.getApplicationContext(), throwable, null, null);
			}
		};
		thread.setUncaughtExceptionHandler(handler);
		thread.setName("Apptentive-FetchAppConfiguration");
		thread.start();
	}

	/**
	 * Sends current Device to the server if it differs from the last time it was sent.
	 *
	 * @param context
	 */
	private static void syncDevice(Context context) {
		Device deviceInfo = DeviceManager.storeDeviceAndReturnDiff(context);
		if (deviceInfo != null) {
			Log.d("Device info was updated.");
			Log.v(deviceInfo.toString());
			ApptentiveDatabase.getInstance(context).addPayload(deviceInfo);
		} else {
			Log.d("Device info was not updated.");
		}
	}

	/**
	 * Sends current Sdk to the server if it differs from the last time it was sent.
	 *
	 * @param context
	 */
	private static void syncSdk(Context context) {
		Sdk sdk = SdkManager.storeSdkAndReturnDiff(context);
		if (sdk != null) {
			Log.d("Sdk was updated.");
			Log.v(sdk.toString());
			ApptentiveDatabase.getInstance(context).addPayload(sdk);
		} else {
			Log.d("Sdk was not updated.");
		}
	}

	/**
	 * Sends current Person to the server if it differs from the last time it was sent.
	 *
	 * @param context
	 */
	private static void syncPerson(Context context) {
		Person person = PersonManager.storePersonAndReturnDiff(context);
		if (person != null) {
			Log.d("Person was updated.");
			Log.v(person.toString());
			ApptentiveDatabase.getInstance(context).addPayload(person);
		} else {
			Log.d("Person was not updated.");
		}
	}

	/**
	 * <p>This type represents a <a href="http://semver.org/">semantic version</a>. It can be initialized
	 * with a string or a long, and there is no limit to the number of parts your semantic version can
	 * contain. The class allows comparison based on semantic version rules.
	 * Valid versions (In sorted order):</p>
	 * <ul>
	 * <li>0</li>
	 * <li>0.1</li>
	 * <li>1.0.0</li>
	 * <li>1.0.9</li>
	 * <li>1.0.10</li>
	 * <li>1.2.3</li>
	 * <li>5</li>
	 * </ul>
	 * Invalid versions:
	 * <ul>
	 * <li>zero</li>
	 * <li>0.1+2015.10.21</li>
	 * <li>1.0.0a</li>
	 * <li>1.0-rc2</li>
	 * <li>1.0.10-SNAPSHOT</li>
	 * <li>5a</li>
	 * <li>FF01</li>
	 * </ul>
	 */
	public static class Version extends JSONObject implements Comparable<Version> {
		public static final String KEY_TYPE = "_type";
		public static final String TYPE = "version";

		public Version() {
		}

		public Version(String json) throws JSONException {
			super(json);
		}

		public Version(long version) {
			super();
			setVersion(version);
		}

		public void setVersion(String version) {
			try {
				put(KEY_TYPE, TYPE);
				put(TYPE, version);
			} catch (JSONException e) {
				Log.e("Error creating Apptentive.Version.", e);
			}
		}

		public void setVersion(long version) {
			setVersion(Long.toString(version));
		}

		public String getVersion() {
			return optString(TYPE, null);
		}

		@Override
		public int compareTo(Version other) {
			String thisVersion = getVersion();
			String thatVersion = other.getVersion();
			String[] thisArray = thisVersion.split("\\.");
			String[] thatArray = thatVersion.split("\\.");

			int maxParts = Math.max(thisArray.length, thatArray.length);
			for (int i = 0; i < maxParts; i++) {
				// If one SemVer has more parts than another, treat pad out the short one with zeros in each slot.
				long left = 0;
				if (thisArray.length > i) {
					left = Long.parseLong(thisArray[i]);
				}
				long right = 0;
				if (thatArray.length > i) {
					right = Long.parseLong(thatArray[i]);
				}
				if (left < right) {
					return -1;
				} else if (left > right) {
					return 1;
				}
			}
			return 0;
		}

		@Override
		public String toString() {
			return getVersion();
		}
	}

	public static class DateTime extends JSONObject implements Comparable<DateTime> {
		public static final String KEY_TYPE = "_type";
		public static final String TYPE = "datetime";
		public static final String SEC = "sec";

		public DateTime(String json) throws JSONException {
			super(json);
		}

		public DateTime(double dateTime) {
			super();
			setDateTime(dateTime);
		}

		public void setDateTime(double dateTime) {
			try {
				put(KEY_TYPE, TYPE);
				put(SEC, dateTime);
			} catch (JSONException e) {
				Log.e("Error creating Apptentive.DateTime.", e);
			}
		}

		public double getDateTime() {
			return optDouble(SEC);
		}

		@Override
		public String toString() {
			return Double.toString(getDateTime());
		}

		@Override
		public int compareTo(DateTime other) {
			double thisDateTime = getDateTime();
			double thatDateTime = other.getDateTime();
			return Double.compare(thisDateTime, thatDateTime);
		}
	}
}
