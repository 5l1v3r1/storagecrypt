/*
 *  Copyright Pierre Sagne (12 december 2014)
 *
 * petrus.dev.fr@gmail.com
 *
 * This software is a computer program whose purpose is to encrypt and
 * synchronize files on the cloud.
 *
 * This software is governed by the CeCILL license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 *
 */

package fr.petrus.lib.core.cloud.implementations.hubic;

import org.joda.time.DateTime;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import fr.petrus.lib.core.cloud.AbstractRemoteStorage;
import fr.petrus.lib.core.cloud.Account;
import fr.petrus.lib.core.cloud.Accounts;
import fr.petrus.lib.core.cloud.RemoteDocument;
import fr.petrus.lib.core.cloud.RemoteStorage;
import fr.petrus.lib.core.cloud.appkeys.AppKeys;
import fr.petrus.lib.core.cloud.appkeys.CloudAppKeys;
import fr.petrus.lib.core.cloud.exceptions.NetworkException;
import fr.petrus.lib.core.cloud.exceptions.OauthException;
import fr.petrus.lib.core.cloud.exceptions.RemoteException;
import fr.petrus.lib.core.cloud.exceptions.UserCanceledException;
import fr.petrus.lib.core.crypto.Crypto;
import fr.petrus.lib.core.db.exceptions.DatabaseConnectionClosedException;
import fr.petrus.lib.core.rest.models.hubic.HubicAccountUsage;
import okhttp3.ResponseBody;
import retrofit2.Response;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.petrus.lib.core.Constants;
import fr.petrus.lib.core.cloud.RemoteChange;
import fr.petrus.lib.core.cloud.RemoteChanges;
import fr.petrus.lib.core.StorageType;
import fr.petrus.lib.core.rest.models.hubic.OpenStackObject;
import fr.petrus.lib.core.rest.models.OauthTokenResponse;
import fr.petrus.lib.core.rest.models.hubic.HubicUser;
import fr.petrus.lib.core.rest.models.hubic.HubicOpenStackCredentials;
import fr.petrus.lib.core.rest.services.hubic.HubicApiService;
import fr.petrus.lib.core.rest.services.hubic.HubicRestClient;
import fr.petrus.lib.core.rest.services.hubic.OpenStackApiService;
import fr.petrus.lib.core.rest.services.hubic.OpenStackRestClient;
import fr.petrus.lib.core.result.ProcessProgressListener;
import fr.petrus.lib.core.utils.StringUtils;

/**
 * The {@link RemoteStorage} implementation for HubiC.
 *
 * @author Pierre Sagne
 * @since 10.02.2015
 */
public class HubicStorage extends AbstractRemoteStorage<HubicStorage, HubicDocument> {
    private static Logger LOG = LoggerFactory.getLogger(HubicStorage.class);

    private HubicApiService apiService;
    private HashMap<String, OpenStackApiService> openStackApiServices;

    /**
     * Creates a new HubicStorage, providing its dependencies.
     *
     * @param crypto       a {@code Crypto} instance
     * @param cloudAppKeys a {@code CloudAppKeys} instance
     * @param accounts     a {@code Accounts} instance
     */
    public HubicStorage(Crypto crypto, CloudAppKeys cloudAppKeys, Accounts accounts) {
        super(crypto, cloudAppKeys, accounts);
        HubicRestClient client = new HubicRestClient();
        apiService = client.getApiService();
        openStackApiServices = new HashMap<>();
    }

    /**
     * Returns the {@code OpenStackApiService}, used to call the underlying API.
     *
     * @return the {@code OpenStackApiService}
     */
    OpenStackApiService getOpenStackApiService(Account account) {
        if (null == account.getOpenStackEndPoint()) {
            return null;
        }
        OpenStackApiService openStackApiService = openStackApiServices.get(account.getOpenStackEndPoint());
        if (null == openStackApiService) {
            OpenStackRestClient openStackRestClient =
                    new OpenStackRestClient(account.getOpenStackEndPoint());
            openStackApiService = openStackRestClient.getApiService();
            openStackApiServices.put(account.getOpenStackEndPoint(), openStackApiService);
        }
        return openStackApiService;
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.HubiC;
    }

    @Override
    public String oauthAuthorizeUrl(boolean mobileVersion, String loginHint) throws RemoteException {
        AppKeys appKeys = cloudAppKeys.getHubicAppKeys();
        if (null == appKeys) {
            throw new RemoteException("App keys not found", RemoteException.Reason.AppKeysNotFound);
        }

        String url = Constants.HUBIC.OAUTH_URL
                + "?client_id=" + appKeys.getClientId()
                + "&redirect_uri=" + appKeys.getRedirectUri()
                + "&response_type=" + Constants.HUBIC.RESPONSE_TYPE
                + "&scope=" + Constants.HUBIC.SCOPE;

        try {
            url += "&state=" + requestCSRFToken();
        } catch (NoSuchAlgorithmException e) {
            LOG.error("Can't generate a CSRF token", e);
        }

        return url;
    }

    @Override
    public String oauthAuthorizeRedirectUri() throws RemoteException {
        AppKeys appKeys = cloudAppKeys.getHubicAppKeys();
        if (null == appKeys) {
            throw new RemoteException("App keys not found", RemoteException.Reason.AppKeysNotFound);
        }
        return appKeys.getRedirectUri();
    }

    @Override
    public Account connectWithAccessCode(Map<String, String> responseParameters)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException {

        AppKeys appKeys = cloudAppKeys.getHubicAppKeys();
        if (null == appKeys) {
            throw new RemoteException("App keys not found", RemoteException.Reason.AppKeysNotFound);
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", responseParameters.get("code"));
        params.put("client_id", appKeys.getClientId());
        params.put("client_secret", appKeys.getClientSecret());
        params.put("redirect_uri", appKeys.getRedirectUri());
        params.put("grant_type", Constants.HUBIC.AUTHORIZATION_CODE_GRANT_TYPE);

        try {
            Response<OauthTokenResponse> response = apiService.getOauthToken(params).execute();
            if (response.isSuccessful()) {
                OauthTokenResponse oauthTokenResponse = response.body();
                String accountName = accountNameFromAccessToken(oauthTokenResponse.access_token);

                Account account = createAccount();
                account.setAccountName(accountName);
                account.setAccessToken(oauthTokenResponse.access_token);
                account.setExpiresInMillis(oauthTokenResponse.expires_in);
                account.setRefreshToken(oauthTokenResponse.refresh_token);
                accounts.add(account);
                return account;
            } else {
                throw new RemoteException("Failed to get oauth token", retrofitErrorReason(response));
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to get oauth token", e);
        }
    }

    @Override
    public String refreshTokensWithAccessCode(Account account, Map<String, String> responseParameters)
            throws RemoteException, DatabaseConnectionClosedException, NetworkException {

        AppKeys appKeys = cloudAppKeys.getHubicAppKeys();
        if (null == appKeys) {
            throw new RemoteException("App keys not found", RemoteException.Reason.AppKeysNotFound);
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", responseParameters.get("code"));
        params.put("client_id", appKeys.getClientId());
        params.put("client_secret", appKeys.getClientSecret());
        params.put("redirect_uri", appKeys.getRedirectUri());
        params.put("grant_type", Constants.HUBIC.AUTHORIZATION_CODE_GRANT_TYPE);

        try {
            Response<OauthTokenResponse> response = apiService.getOauthToken(params).execute();
            if (response.isSuccessful()) {
                OauthTokenResponse oauthTokenResponse = response.body();
                String accountName = accountNameFromAccessToken(oauthTokenResponse.access_token);

                if (null!=accountName && accountName.equals(account.getAccountName())) {
                    account.setAccessToken(oauthTokenResponse.access_token);
                    account.setExpiresInMillis(oauthTokenResponse.expires_in);
                    account.setRefreshToken(oauthTokenResponse.refresh_token);
                    account.update();
                }
                return accountName;
            } else {
                throw new RemoteException("Failed to get oauth token", retrofitErrorReason(response));
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to get oauth token", e);
        }
    }

    @Override
    public String accountNameFromAccessToken(String accessToken)
            throws RemoteException, NetworkException {
        if (null==accessToken) {
            throw new RemoteException("Failed to get account name : access token is null",
                    RemoteException.Reason.AccessTokenIsNull);
        }

        try {
            Response<HubicUser> response = apiService.getAccountInfo("Bearer " + accessToken).execute();
            if (response.isSuccessful()) {
                return response.body().email;
            } else {
                throw new RemoteException("Failed to get account name", retrofitErrorReason(response));
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to get account name", e);
        }
    }


    @Override
    public Account refreshToken(String accountName)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        if (null==accountName) {
            throw new RemoteException("Failed to refresh access token : account name is null",
                    RemoteException.Reason.AccountNameIsNull);
        }

        Account account = account(accountName);
        if (null==account) {
            throw new RemoteException("Failed to refresh access token : account is null",
                    RemoteException.Reason.AccountNotFound);
        }

        AppKeys appKeys = cloudAppKeys.getHubicAppKeys();
        if (null==appKeys) {
            throw new RemoteException("App keys not found", RemoteException.Reason.AppKeysNotFound);
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("refresh_token", account.getRefreshToken());
        params.put("client_id", appKeys.getClientId());
        params.put("client_secret", appKeys.getClientSecret());
        params.put("grant_type", Constants.HUBIC.REFRESH_TOKEN_GRANT_TYPE);

        try {
            Response<OauthTokenResponse> response = apiService.getOauthToken(params).execute();
            if (response.isSuccessful()) {
                OauthTokenResponse oauthTokenResponse = response.body();
                if (null!=oauthTokenResponse.access_token) {
                    account.setAccessToken(oauthTokenResponse.access_token);
                }
                if (null!=oauthTokenResponse.expires_in) {
                    account.setExpiresInMillis(oauthTokenResponse.expires_in);
                }
                if (null!=oauthTokenResponse.refresh_token) {
                    account.setRefreshToken(oauthTokenResponse.refresh_token);
                }
                account.update();

                return account;
            } else {
                if (isInvalidGrantOauthError(response)) {
                    throw new OauthException("Failed to refresh access token",
                            OauthException.Reason.RefreshTokenInvalidGrant, account);
                }
                throw remoteException(account, response, "Failed to refresh access token");
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to refresh access token", e);
        }
    }

    @Override
    public void revokeToken(String accountName) throws RemoteException {
        LOG.debug("HubiC tokens don't get revoked here.");
    }

    /**
     * Sends a request to get the OpenStack credentials (access token, endpoint, user name),
     * adds it to the account, persists it to the database, and returns the updated account.
     *
     * @param accountName the account user name
     * @return the updated account
     * @throws RemoteException                    if any error occurs when calling the underlying API
     * @throws DatabaseConnectionClosedException if the database connection is closed
     */
    public Account getOpenStackCredentials(String accountName)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        Account account = refreshedAccount(accountName);
        try {
            Response<HubicOpenStackCredentials> response = apiService.getOpenStackCredentials(account.getAuthHeader()).execute();
            if (response.isSuccessful()) {
                HubicOpenStackCredentials hubicOpenStackCredentials = response.body();
                if (null != hubicOpenStackCredentials.token) {
                    account.setOpenStackAccessToken(hubicOpenStackCredentials.token);
                }
                if (null != hubicOpenStackCredentials.expires) {
                    LOG.debug("OpenStack expires : {}", hubicOpenStackCredentials.expires);
                    DateTime dateTime = new DateTime(hubicOpenStackCredentials.expires);
                    Long expiresUtc = dateTime.toDate().getTime();
                    account.setOpenStackAccessTokenExpirationTime(expiresUtc);
                    LOG.debug("Openstack credentials expire in {} seconds.",
                    (expiresUtc - System.currentTimeMillis())/1000);
                }
                if (null != hubicOpenStackCredentials.endpoint) {
                    LOG.debug("OpenStack endpoint : {}", hubicOpenStackCredentials.endpoint);
                    URL hubicOpenStackEndPointUrl = new URL(hubicOpenStackCredentials.endpoint);
                    URL endPointUrl = new URL(hubicOpenStackEndPointUrl.getProtocol(),
                            hubicOpenStackEndPointUrl.getHost(), hubicOpenStackEndPointUrl.getPort(), "/");
                    account.setOpenStackEndPoint(endPointUrl.toString());
                    File urlFile = new File(hubicOpenStackEndPointUrl.getFile());
                    account.setOpenStackAccount(urlFile.getName());
                    LOG.debug("OpenStack base endpoint : {}", account.getOpenStackEndPoint());
                    LOG.debug("OpenStack account : {}", account.getOpenStackAccount());
                }
                account.update();

                return account;
            } else {
                throw remoteException(account, response, "Failed to get OpenStack credentials");
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to get OpenStack credentials", e);
        }
    }

    /**
     * Sends a request to refresh the OpenStack credentials (access token, endpoint, user name),
     * adds it to the account, persists it to the database, and returns the updated account.
     *
     * @param accountName the account user name
     * @return the updated account
     * @throws RemoteException                    if any error occurs when calling the underlying API
     * @throws DatabaseConnectionClosedException if the database connection is closed
     */
    public Account getRefreshedOpenStackAccount(String accountName)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        return getRefreshedOpenStackAccount(accountName, false);
    }

    /**
     * Sends a request to refresh the OpenStack credentials (access token, endpoint, user name),
     * adds it to the account, persists it to the database, and returns the updated account.
     *
     * <p>If {@code refreshAllTokens} is true, also requests a new OAuth2 access token if needed
     *
     * @param accountName the account user name
     * @param refreshAllTokens if set to true, also request a new OAuth2 access token if needed
     * @return the updated account
     * @throws RemoteException                    if any error occurs when calling the underlying API
     * @throws DatabaseConnectionClosedException if the database connection is closed
     */
    public Account getRefreshedOpenStackAccount(String accountName, boolean refreshAllTokens)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        if (null==accountName) {
            throw new RemoteException("Failed to get refreshed OpenStack account : account name is null",
                    RemoteException.Reason.AccountNameIsNull);
        }

        Account account;
        if (refreshAllTokens) {
            account = refreshedAccount(accountName);
        } else {
            account = account(accountName);
        }
        if (null==account) {
            throw new RemoteException("Failed to get refreshed OpenStack account : account is null",
                    RemoteException.Reason.AccountNotFound);
        }

        if (account.isOpenStackAccessTokenExpired()) {
            account = getOpenStackCredentials(accountName);
        }

        return account;
    }

    @Override
    public Account refreshQuota(Account account)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        Account refreshedAccount = refreshedAccount(account.getAccountName());
        try {
            Response<HubicAccountUsage> response = apiService.getAccountUsage(refreshedAccount.getAuthHeader()).execute();
            if (response.isSuccessful()) {
                HubicAccountUsage accountUsage = response.body();
                if (null != accountUsage) {
                    refreshedAccount.setQuotaAmount(accountUsage.quota);
                    refreshedAccount.setQuotaUsed(accountUsage.used);
                    refreshedAccount.update();
                    return refreshedAccount;
                } else {
                    throw remoteException(account, response, "Failed to get account info");
                }
            } else {
                throw remoteException(account, response, "Failed to get account info");
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to get account info", e);
        }
    }

    /**
     * Returns a virtual folder.
     *
     * <p>The returned folder is qualified as "virtual" because it is not physically created on the
     * HubiC account.
     *
     * <p>In fact it is not possible to create a folder on HubiC. The folders exist as parts of the
     * path of files.
     *
     * @param accountName the account user name
     * @param path        the path of the virtual folder
     * @return the virtual folder
     */
    public HubicDocument virtualFolder(String accountName, String path) {
        HubicDocument folder = new HubicDocument(this);
        folder.setAccountName(accountName);
        folder.setFolder(true);
        folder.setPath(StringUtils.trimSlashes(path));
        folder.setName(HubicDocument.getNameFromPath(folder.getPath()));

        return folder;
    }

    @Override
    public HubicDocument rootFolder(String accountName) {
        HubicDocument rootFolder = new HubicDocument(this);
        rootFolder.setAccountName(accountName);
        rootFolder.setFolder(true);
        rootFolder.setName("");
        rootFolder.setPath("");

        return rootFolder;
    }

    @Override
    public HubicDocument appFolder(String accountName) {
        return virtualFolder(accountName, Constants.FILE.APP_DIR_NAME);
    }

    @Override
    public HubicDocument document(String accountName, String path)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        return file(accountName, path);
    }

    @Override
    public HubicDocument folder(String accountName, String path) throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        HubicDocument appFolder = appFolder(accountName);
        if (StringUtils.trimSlashes(path).equals(appFolder.getPath())) {
            return appFolder;
        } else {
            HubicDocument folder = virtualFolder(accountName, path);
            RemoteDocument metadataFile = folder.childFile(Constants.STORAGE.FOLDER_METADATA_FILE_NAME);
            if (null != metadataFile) {
                return folder;
            }
            return null;
        }
    }

    @Override
    public HubicDocument file(String accountName, String path)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        Account account = getRefreshedOpenStackAccount(accountName);
        OpenStackApiService openStackApiService = getOpenStackApiService(account);
        try {
            Response<Void> response = openStackApiService.getDocument(account.getOpenStackAccessToken(),
                    account.getOpenStackAccount(), Constants.HUBIC.OPENSTACK_CONTAINER,
                    StringUtils.trimSlashes(path)).execute();
            if (response.isSuccessful()) {
                return new HubicDocument(this, accountName, path, response);
            } else {
                throw remoteException(account, response, "Failed to get file");
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to get file", e);
        }
    }

    @Override
    public RemoteChanges changes(String accountName, String lastChangeId, ProcessProgressListener listener)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, UserCanceledException, OauthException {

        Account account = getRefreshedOpenStackAccount(accountName);

        RemoteChanges changes = new RemoteChanges(false);
        long startChangeTime = -1L;
        if (null!=lastChangeId) {
            startChangeTime = Long.parseLong(lastChangeId);
        }
        long lastChangeFoundTime = -1L;

        OpenStackApiService openStackApiService = getOpenStackApiService(account);

        try {
            Response<List<OpenStackObject>> response = openStackApiService.getFolderRecursiveChildren(
                    account.getOpenStackAccessToken(),
                    account.getOpenStackAccount(),
                    Constants.HUBIC.OPENSTACK_CONTAINER,
                    Constants.FILE.APP_DIR_NAME).execute();
            if (response.isSuccessful()) {
                List<OpenStackObject> openStackObjects = response.body();
                if (null!=listener) {
                    listener.onSetMax(0, openStackObjects.size());
                    listener.pauseIfNeeded();
                    if (listener.isCanceled()) {
                        throw new UserCanceledException("Canceled");
                    }
                }
                Set<String> folders = new HashSet<>();
                for (OpenStackObject openStackObject : openStackObjects) {
                    HubicDocument document = new HubicDocument(this, account.getAccountName(), openStackObject);
                    long documentModificationTime = document.getModificationTime();
                    if (documentModificationTime>lastChangeFoundTime) {
                        lastChangeFoundTime = documentModificationTime;
                    }
                    HubicDocument parent = virtualFolder(accountName, document.getParentPath());
                    if (!folders.contains(document.getParentPath())) {
                        folders.add(document.getParentPath());
                        changes.addChange(RemoteChange.modification(parent));
                    }
                    changes.addChange(RemoteChange.modification(document));
                    if (null != listener) {
                        listener.onSetMax(0, changes.getChanges().size());
                        listener.onProgress(0, changes.getChanges().size());
                        listener.pauseIfNeeded();
                        if (listener.isCanceled()) {
                            throw new UserCanceledException("Canceled");
                        }
                    }
                }

                if (lastChangeFoundTime>-1L) {
                    changes.setLastChangeId(String.valueOf(lastChangeFoundTime));
                } else {
                    changes.setLastChangeId(String.valueOf(startChangeTime));
                }
                return changes;
            } else {
                throw remoteException(account, response, "Failed to get documents");
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to get documents", e);
        }
    }

    @Override
    public void deleteFolder(String accountName, String path)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        HubicDocument folder = virtualFolder(accountName, path);
        try {
            List<HubicDocument> children = folder.childDocuments(null);
            if (children.size() == 1 && Constants.STORAGE.FOLDER_METADATA_FILE_NAME.equals(children.get(0).getName())) {
                deleteFile(accountName, (children.get(0)).getPath());
            }
        } catch (UserCanceledException e) {
            LOG.error("Canceled operation : this should not happen", e);
        }
    }

    @Override
    public void deleteFile(String accountName, String path)
            throws DatabaseConnectionClosedException, RemoteException, NetworkException, OauthException {
        Account account = getRefreshedOpenStackAccount(accountName);
        OpenStackApiService openStackApiService = getOpenStackApiService(account);
        try {
            Response<ResponseBody> response = openStackApiService.deleteDocument(
                    account.getOpenStackAccessToken(),
                    account.getOpenStackAccount(),
                    Constants.HUBIC.OPENSTACK_CONTAINER,
                    StringUtils.trimSlashes(path)).execute();
            if (!response.isSuccessful()) {
                RemoteException remoteException = remoteException(account, response, "Failed to delete file");
                if (!remoteException.isNotFoundError()) {
                    throw remoteException;
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new NetworkException("Failed to delete file", e);
        }
    }
}