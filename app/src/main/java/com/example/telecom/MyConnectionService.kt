package com.example.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.*
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class MyConnectionService : ConnectionService() {

    private val TAG = "MyConnectionService"

    companion object {
        var activeConnection: MyConnection? = null
        var onCallStateChangedListener: ((Boolean) -> Unit)? = null
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateIncomingConnection initiated.")
        val connection = MyConnection()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            connection.connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or Connection.CAPABILITY_MUTE
        }
        connection.audioModeIsVoip = true
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setInitializing()
        activeConnection = connection
        onCallStateChangedListener?.invoke(true)
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateOutgoingConnection initiated.")
        val connection = MyConnection()
        connection.audioModeIsVoip = true
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setDialing()
        activeConnection = connection
        onCallStateChangedListener?.invoke(true)
        return connection
    }
}

@RequiresApi(Build.VERSION_CODES.M)
class MyConnection : Connection() {

    private val TAG = "MyConnection"

    init {
        setInitializing()
    }

    override fun onAnswer() {
        Log.i(TAG, "onAnswer: Call was answered.")
        setActive()
    }

    override fun onReject() {
        Log.i(TAG, "onReject: Call was rejected.")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        MyConnectionService.activeConnection = null
        MyConnectionService.onCallStateChangedListener?.invoke(false)
    }

    override fun onDisconnect() {
        Log.i(TAG, "onDisconnect: Connected call disconnected.")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        MyConnectionService.activeConnection = null
        MyConnectionService.onCallStateChangedListener?.invoke(false)
    }

    override fun onHold() {
        Log.i(TAG, "onHold: Call put on hold.")
        setOnHold()
    }

    override fun onUnhold() {
        Log.i(TAG, "onUnhold: Call taken off hold.")
        setActive()
    }
}
