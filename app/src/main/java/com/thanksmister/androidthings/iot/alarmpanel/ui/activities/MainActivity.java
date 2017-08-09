/*
 * <!--
 *   ~ Copyright (c) 2017. ThanksMister LLC
 *   ~
 *   ~ Licensed under the Apache License, Version 2.0 (the "License");
 *   ~ you may not use this file except in compliance with the License. 
 *   ~ You may obtain a copy of the License at
 *   ~
 *   ~ http://www.apache.org/licenses/LICENSE-2.0
 *   ~
 *   ~ Unless required by applicable law or agreed to in writing, software distributed 
 *   ~ under the License is distributed on an "AS IS" BASIS, 
 *   ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 *   ~ See the License for the specific language governing permissions and 
 *   ~ limitations under the License.
 *   -->
 */

package com.thanksmister.androidthings.iot.alarmpanel.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import com.thanksmister.androidthings.iot.alarmpanel.BaseActivity;
import com.thanksmister.androidthings.iot.alarmpanel.R;
import com.thanksmister.androidthings.iot.alarmpanel.constants.Constants;
import com.thanksmister.androidthings.iot.alarmpanel.network.model.SubscriptionData;
import com.thanksmister.androidthings.iot.alarmpanel.tasks.SubscriptionDataTask;
import com.thanksmister.androidthings.iot.alarmpanel.ui.fragments.ControlsFragment;
import com.thanksmister.androidthings.iot.alarmpanel.ui.fragments.InformationFragment;
import com.thanksmister.androidthings.iot.alarmpanel.ui.fragments.SensorsFragment;
import com.thanksmister.androidthings.iot.alarmpanel.ui.views.AlarmTriggeredView;
import com.thanksmister.androidthings.iot.alarmpanel.utils.AlarmUtils;
import com.thanksmister.androidthings.iot.alarmpanel.utils.MqttUtils;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import timber.log.Timber;

public class MainActivity extends BaseActivity implements ControlsFragment.OnControlsFragmentListener {
    
    private static final String FRAGMENT_CONTROLS = "com.thanksmister.fragment.FRAGMENT_CONTROLS";
    private static final String FRAGMENT_SENSORS = "com.thanksmister.fragment.FRAGMENT_SENSORS";
    private static final String FRAGMENT_INFORMATION = "com.thanksmister.fragment.FRAGMENT_INFORMATION";

    @Bind(R.id.buttonSettings)
    ImageButton buttonSettings;

    @Bind(R.id.buttonLogs)
    ImageButton buttonLogs;
    
    @Bind(R.id.triggeredView)
    View triggeredView;
    
    @OnClick(R.id.buttonSettings)
    void buttonSettingsClicked() {
        Intent intent = SettingsActivity.createStartIntent(MainActivity.this);
        startActivity(intent);
    }

    @OnClick(R.id.buttonLogs)
    void buttonLogsClicked() {
        Intent intent = LogActivity.createStartIntent(MainActivity.this);
        startActivity(intent);
    }

    private SubscriptionDataTask subscriptionDataTask;
    private MqttAndroidClient mqttAndroidClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
            getSupportActionBar().setDisplayUseLogoEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        
        getStoreManager().reset();
        
        // TODO show first time dialog then go to settings
        /*if(getConfiguration().isFirstTime()) {
            Intent intent = SettingsActivity.createStartIntent(MainActivity.this);
            startActivity(intent);
            return;
        }*/

        // TODO move these to settings
        getConfiguration().setCommandTopic(AlarmUtils.COMMAND_TOPIC);
        getConfiguration().setStateTopic(AlarmUtils.STATE_TOPIC);
        getConfiguration().setUserName("homeassistant");
        getConfiguration().setPassword("3355");
        getConfiguration().setPort(Constants.PORT);
        getConfiguration().setBroker("192.168.86.118");
        
       if (savedInstanceState == null) {
            ControlsFragment controlsFragment = ControlsFragment.newInstance();
            InformationFragment informationFragment = InformationFragment.newInstance();
            SensorsFragment sensorsFragment = SensorsFragment.newInstance();
            getSupportFragmentManager().beginTransaction().replace(R.id.controlContainer, controlsFragment, FRAGMENT_CONTROLS).commit();
            getSupportFragmentManager().beginTransaction().replace(R.id.informationContainer, informationFragment, FRAGMENT_SENSORS).commit();
            getSupportFragmentManager().beginTransaction().replace(R.id.sensorsContainer, sensorsFragment, FRAGMENT_INFORMATION).commit();
        }
        
        //SyncService.requestSyncNow(this);
        makeMqttConnection();
    }
    
    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (this.subscriptionDataTask != null) {
            this.subscriptionDataTask.cancel(true);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.global, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_settings) {
            Intent intent = SettingsActivity.createStartIntent(MainActivity.this);
            startActivity(intent);
        } 
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void publishArmedHome() {
        String topic = AlarmUtils.COMMAND_TOPIC;
        String message = AlarmUtils.COMMAND_ARM_HOME;
        publishMessage(topic, message);
    }

    @Override
    public void publishArmedAway() {
        String topic = AlarmUtils.COMMAND_TOPIC;
        String message = AlarmUtils.COMMAND_ARM_AWAY;
        publishMessage(topic, message);
    }

    @Override
    public void publishDisarmed() {
        String topic = AlarmUtils.COMMAND_TOPIC;
        String message = AlarmUtils.COMMAND_DISARM;
        publishMessage(topic, message);
    }
    
    private void makeMqttConnection() {
        final boolean tlsConnection = getConfiguration().getTlsConnection();
        final String serverUri;
        if(tlsConnection) {
            serverUri = "ssl://" + getConfiguration().getBroker() + ":" + getConfiguration().getPort();
        } else {
            serverUri = "tcp://" + getConfiguration().getBroker() + ":" + getConfiguration().getPort();
        }

        Timber.d("Server Uri: " + serverUri);
        final String clientId = getConfiguration().getClientId();
        final String topic = getConfiguration().getStateTopic();

        MqttConnectOptions mqttConnectOptions = MqttUtils.getMqttConnectOptions(getConfiguration().getUserName(), getConfiguration().getPassword());
        mqttAndroidClient = MqttUtils.getMqttAndroidClient(getApplicationContext(), serverUri, clientId, topic, new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    Timber.d("Reconnected to : " + serverURI);
                    // Because Clean Session is true, we need to re-subscribe
                    subscribeToTopic(topic);
                } else {
                    Timber.d("Connected to: " + serverURI);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                Timber.d("The Connection was lost.");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                Timber.i("Sent Message : " + topic + " : " + new String(message.getPayload()));
                //subscriptionDataTask = getUpdateMqttDataTask();
                //subscriptionDataTask.execute(new SubscriptionData(topic, new String(message.getPayload()), String.valueOf(message.getId())));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        try {
            mqttAndroidClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                    disconnectedBufferOptions.setBufferEnabled(true);
                    disconnectedBufferOptions.setBufferSize(100);
                    disconnectedBufferOptions.setPersistBuffer(false);
                    disconnectedBufferOptions.setDeleteOldestMessages(false);
                    mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
                    subscribeToTopic(topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Timber.e("Failed to connect to: " + serverUri + " exception: " + exception.getMessage());
                }
            });

        } catch (MqttException ex){
            ex.printStackTrace();
        }
    }

    private void subscribeToTopic(final String topic) {
        try {
            mqttAndroidClient.subscribe(topic, 0, new IMqttMessageListener() {
                @Override
                public void messageArrived(final String topic, final MqttMessage message) throws Exception {
                    // message Arrived!
                    Timber.i("Subscribe Message message : " + topic + " : " + new String(message.getPayload()));
                    subscriptionDataTask = getUpdateMqttDataTask();
                    subscriptionDataTask.execute(new SubscriptionData(topic, new String(message.getPayload()), String.valueOf(message.getId())));
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if(AlarmUtils.hasSupportedStates(new String(message.getPayload()))) {
                                handleStateChange(new String(message.getPayload()));
                            }
                        }
                    });
                }
            });
        } catch (MqttException ex){
            Timber.e("Exception whilst subscribing");
            ex.printStackTrace();
            hideProgressDialog();
        }
    }

    private void publishMessage(String publishTopic, String publishMessage) {
        try {
            MqttMessage message = new MqttMessage();
            message.setPayload(publishMessage.getBytes());
            mqttAndroidClient.publish(publishTopic, message);
            Timber.d("Message Published: " + publishTopic);
            if(!mqttAndroidClient.isConnected()){
                Timber.d(mqttAndroidClient.getBufferedMessageCount() + " messages in buffer.");
            }
        } catch (MqttException e) {
            Timber.e("Error Publishing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle the alarm triggered state
     * @param state
     */
    @AlarmUtils.AlarmStates
    private void handleStateChange(String state) {
        Timber.d("handleStateChange States: " + state);
        if(AlarmUtils.STATE_TRIGGERED.equals(state)) {
            triggeredView.setVisibility(View.VISIBLE);
            int code = getConfiguration().getAlarmCode();
            final AlarmTriggeredView disarmView = (AlarmTriggeredView) findViewById(R.id.alarmTriggeredView);
            disarmView.setListener(new AlarmTriggeredView.ViewListener() {
                @Override
                public void onComplete() {
                    publishDisarmed();
                }
                @Override
                public void onError() {
                    Toast.makeText(MainActivity.this, R.string.toast_code_invalid, Toast.LENGTH_SHORT).show();
                }
            });
            disarmView.setCode(code);
        } else {
            triggeredView.setVisibility(View.GONE);
        }
    }
    
    private SubscriptionDataTask getUpdateMqttDataTask() {
        SubscriptionDataTask dataTask = new SubscriptionDataTask(getStoreManager());
        dataTask.setOnExceptionListener(new SubscriptionDataTask.OnExceptionListener() {
            public void onException(Exception exception) {
                Timber.e("Update Exception: " + exception.getMessage());
            }
        });
        dataTask.setOnCompleteListener(new SubscriptionDataTask.OnCompleteListener<Boolean>() {
            public void onComplete(Boolean response) {
                hideProgressDialog();
                if (!response) {
                    Timber.e("Update Exception response: " + response);
                }
            }
        });
        return dataTask;
    }
}