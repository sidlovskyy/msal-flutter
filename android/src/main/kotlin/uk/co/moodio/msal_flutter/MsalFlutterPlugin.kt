package uk.co.moodio.msal_flutter

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.IPublicClientApplication

@Suppress("SpellCheckingInspection")
class MsalFlutterPlugin: MethodCallHandler {
    companion object
    {
        lateinit var mainActivity : Activity
        lateinit var msalApp: IMultipleAccountPublicClientApplication

        fun isClientInitialized() = ::msalApp.isInitialized

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            Log.d("MsalFlutter","Registering plugin")
            val channel = MethodChannel(registrar.messenger(), "msal_flutter")
            channel.setMethodCallHandler(MsalFlutterPlugin())
            mainActivity = registrar.activity()
        }

        fun getAuthCallback(result: Result) : AuthenticationCallback
        {
            Log.d("MsalFlutter", "Getting the auth callback object")
            return object : AuthenticationCallback
            {
                override fun onSuccess(authenticationResult : IAuthenticationResult){
                    Log.d("MsalFlutter", "Authentication successful")
                    Handler(Looper.getMainLooper()).post {
                        result.success(authenticationResult.accessToken)
                    }
                }

                override fun onError(exception : MsalException)
                {
                    Log.d("MsalFlutter","Error logging in! $exception")
                    Handler(Looper.getMainLooper()).post {
                        result.error("AUTH_ERROR", "Authentication failed", exception.localizedMessage)
                    }
                }

                override fun onCancel(){
                    Log.d("MsalFlutter", "Cancelled")
                    Handler(Looper.getMainLooper()).post {
                        result.error("CANCELLED", "User cancelled", null)
                    }
                }
            }
        }

        private fun getApplicationCreatedListener(result: Result) : IPublicClientApplication.ApplicationCreatedListener {
            Log.d("MsalFlutter", "Getting the created listener")

            return object : IPublicClientApplication.ApplicationCreatedListener
            {
                override fun onCreated(application: IPublicClientApplication) {
                    Log.d("MsalFlutter", "Created successfully")
                    msalApp = application as MultipleAccountPublicClientApplication
                    result.success(true)
                }

                override fun onError(exception: MsalException?) {
                    Log.d("MsalFlutter", "Initialize error: $exception")
                    result.error("INIT_ERROR", "Error initializting client", exception?.localizedMessage)
                }
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result)
    {
        val scopesArg : ArrayList<String>? = call.argument("scopes")
        val scopes: Array<String>? = scopesArg?.toTypedArray()
        val clientId : String? = call.argument("clientId")
        val authority : String? = call.argument("authority")
        val redirectUrl : String? = call.argument("redirectUrl")

        Log.d("MsalFlutter","Got scopes: $scopes")
        Log.d("MsalFlutter","Got cleintId: $clientId")
        Log.d("MsalFlutter","Got authority: $authority")

        when(call.method){
            "logout" -> Thread(Runnable{logout(result)}).start()
            "initialize" -> initialize(clientId, authority, redirectUrl, result)
            "acquireToken" -> Thread(Runnable {acquireToken(scopes, result)}).start()
            "acquireTokenSilent" -> Thread(Runnable {acquireTokenSilent(scopes, result)}).start()
            else -> result.notImplemented()
        }

    }

    private fun acquireToken(scopes : Array<String>?, result: Result)
    {
        Log.d("MsalFlutter", "acquire token called")

        // check if client has been initialized
        if(!isClientInitialized()){
            Log.d("MsalFlutter","Client has not been initialized")
            Handler(Looper.getMainLooper()).post {
                result.error("NO_CLIENT", "Client must be initialized before attempting to acquire a token.", null)
            }
        }

        //check scopes
        if(scopes == null){
            Log.d("MsalFlutter", "no scope")
            result.error("NO_SCOPE", "Call must include a scope", null)
            return
        }

        //remove old accounts
        while(msalApp.accounts.any()){
            Log.d("MsalFlutter","Removing old account")
            msalApp.removeAccount(msalApp.accounts.first())
        }

        //acquire the token
        msalApp.acquireToken(mainActivity, scopes, getAuthCallback(result))
    }

    private fun acquireTokenSilent(scopes : Array<String>?, result: Result)
    {
        Log.d("MsalFlutter", "Called acquire token silent")

        // check if client has been initialized
        if(!isClientInitialized()){
            Log.d("MsalFlutter","Client has not been initialized")
            Handler(Looper.getMainLooper()).post {
                result.error("NO_CLIENT", "Client must be initialized before attempting to acquire a token.", null)
            }
        }

        //check the scopes
        if(scopes == null){
            Log.d("MsalFlutter", "no scope")
            Handler(Looper.getMainLooper()).post {
                result.error("NO_SCOPE", "Call must include a scope", null)
            }
            return
        }

        //ensure accounts exist
        if(msalApp.accounts.isEmpty()){
            Handler(Looper.getMainLooper()).post {
                result.error("NO_ACCOUNT", "No account is available to acquire token silently for", null)
            }
            return
        }

        //acquire the token and return the result
        val res = msalApp.acquireTokenSilent(scopes, msalApp.accounts[0], msalApp.configuration.defaultAuthority.authorityURL.toString())
        Handler(Looper.getMainLooper()).post {
            result.success(res.accessToken)
        }
    }

    private fun initialize(clientId: String?, authority: String?, redirectUrl: String?, result: Result)
    {
        //ensure clientid provided
        if(clientId == null){
            Log.d("MsalFlutter","error no clientId")
            result.error("NO_CLIENTID", "Call must include a clientId", null)
            return
        }

        //ensure redirectUrl provided
        if(redirectUrl == null){
            Log.d("MsalFlutter","error no redirectUrl")
            result.error("NO_REDIRECT_URL", "Call must include a redirectUrl", null)
            return
        }

        //if already initialized, ensure clientid hasn't changed
        if(isClientInitialized()){
            Log.d("MsalFlutter","Client already initialized.")
            if(msalApp.configuration.clientId == clientId)
            {
                result.success(true)
            } else {
                result.error("CHANGED_CLIENTID", "Attempting to initialize with multiple clientIds.", null)
            }
        }

        Log.d("MsalFlutter", "Creating with: $clientId - $authority, redirect: $redirectUrl)")
        PublicClientApplication.create(mainActivity.applicationContext, clientId, authority, redirectUrl, getApplicationCreatedListener(result))
    }


    private fun logout(result: Result){
        while(msalApp.accounts.any()){
            Log.d("MsalFlutter","Removing old account")
            msalApp.removeAccount(msalApp.accounts.first())
        }
        Handler(Looper.getMainLooper()).post {
            result.success(true)
        }
    }
}
