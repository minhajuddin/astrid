package com.todoroo.astrid.gtasks.auth;

import java.io.IOException;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.os.Bundle;

import com.google.api.client.googleapis.extensions.android2.auth.GoogleAccountManager;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.gtasks.GtasksPreferenceService;
import com.todoroo.astrid.gtasks.api.GtasksService;

public class GtasksTokenValidator {

    /**
     * Invalidates and then revalidates the auth token for the currently logged in user
     * Shouldn't be called from the main thread--will block on network calls
     * @param token
     * @return valid token on success, null on failure
     */
    public static String validateAuthToken(String token) {
        GoogleAccountManager accountManager = new GoogleAccountManager(ContextManager.getContext());

        GtasksService testService = new GtasksService(token);
        try {
            testService.ping();
            return token;
        } catch (IOException i) { //If fail, token may have expired -- get a new one and return that
            String accountName = Preferences.getStringValue(GtasksPreferenceService.PREF_USER_NAME);
            Account a = accountManager.getAccountByName(Preferences.getStringValue(GtasksPreferenceService.PREF_USER_NAME));
            if (a == null) {
                System.err.println("Account for name: " + accountName + " not found");
                return null;
            }

            accountManager.invalidateAuthToken(token);
            AccountManagerFuture<Bundle> future = accountManager.manager.getAuthToken(a, GtasksService.AUTH_TOKEN_TYPE, true, null, null);

            try {
                if (future.getResult().containsKey(AccountManager.KEY_AUTHTOKEN)) {
                    Bundle result = future.getResult();
                    token = result.getString(AccountManager.KEY_AUTHTOKEN);
                    testService = new GtasksService(token);
                    try { //Make sure the new token works--if not, we may have network problems
                        testService.ping();
                        return token;
                    } catch (IOException i2) {
                        i2.printStackTrace();
                        return null;
                    }
                } else {
                    System.err.println("Future did not have key for authtoken");
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }

        }

        System.err.println("Gtasks token validation fell through all logic");
        return null;
    }

}
