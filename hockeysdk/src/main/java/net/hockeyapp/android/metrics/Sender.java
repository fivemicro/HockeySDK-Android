package net.hockeyapp.android.metrics;

import android.annotation.TargetApi;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import net.hockeyapp.android.utils.AsyncTaskUtils;
import net.hockeyapp.android.utils.HockeyLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * <h3>Description</h3>
 * <p/>
 * Transmits given telemetry data to the ingestion endpoint. Transmission is done
 * asynchronously to not block the main thread.
 **/

public class Sender {

    /**
     * Default endpoint to send the telemetry data to.
     */
    static final String DEFAULT_ENDPOINT_URL = "https://gate.hockeyapp.net/v2/track";
    /**
     * Read timeout for transmission.
     */
    static final int DEFAULT_SENDER_READ_TIMEOUT = 10 * 1000;
    /**
     * Connect timeout for transmission.
     */
    static final int DEFAULT_SENDER_CONNECT_TIMEOUT = 15 * 1000;
    /**
     * The max number of requests to perform in parallel.
     */
    static final int MAX_REQUEST_COUNT = 10;
    /**
     * The logging tag.
     */
    private static final String TAG = "HockeyApp-Metrics";
    /**
     * Persistence object used to reserve, free, or delete files.
     */
    protected WeakReference<Persistence> mWeakPersistence;
    /**
     * Thread safe counter to keep track of number of concurrent operations.
     */
    private AtomicInteger mRequestCount;
    /**
     * Custom ingestion endpoint URL.
     */
    private String mCustomServerURL;

    /**
     * Creates and initializes a new instance.
     * <p/>
     * Persistence has to be configured separately and has to be set directly
     * after initialization.
     */
    protected Sender() {
        mRequestCount = new AtomicInteger(0);
    }

    /**
     * Triggers sending of available telemetry data in an AsyncTask. Checks with persistence
     * for available data, if the max amount of concurrent requests is not reached yet.
     * Does nothing, if the maximum number of concurrent requests is already reached or exceeded.
     */
    protected void triggerSending() {
        if (requestCount() < MAX_REQUEST_COUNT) {
            AsyncTaskUtils.execute(
                    new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... params) {
                            // Send the persisted data
                            sendAvailableFiles();
                            return null;
                        }
                    }
            );
        } else {
            HockeyLog.debug(TAG, "We have already 10 pending requests, not sending anything.");
        }
    }

    protected void triggerSendingForTesting(final HttpURLConnection connection, final File file, final String persistedData) {
        if (requestCount() < MAX_REQUEST_COUNT) {
            mRequestCount.getAndIncrement();

            AsyncTaskUtils.execute(
                    new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... params) {
                            // Send the persisted data
                            send(connection, file, persistedData);
                            return null;
                        }
                    }
            );
        }
    }

    /**
     * Checks the persistence for available files and sends them.
     */
    protected void sendAvailableFiles() {
        if (this.getPersistence() != null) {
            File fileToSend = this.getPersistence().nextAvailableFileInDirectory();
            String persistedData = loadData(fileToSend);
            HttpURLConnection connection = createConnection();

            if ((persistedData != null) && (connection != null)) {
                send(connection, fileToSend, persistedData);
            }
        }
    }

    /**
     * Send a file to the ingestion endpoint.
     *
     * @param connection
     * @param file
     * @param persistedData
     */
    protected void send(HttpURLConnection connection, File file, String persistedData) {
        logRequest(connection, persistedData);
        if (connection != null && file != null && persistedData != null) {
            mRequestCount.getAndIncrement();
            try {
                // Starts the query
                connection.connect();
                // read the response code while we're ready to catch the IO exception
                int responseCode = connection.getResponseCode();
                // process the response
                onResponse(connection, responseCode, persistedData, file);
            } catch (IOException e) {
                //Probably offline
                HockeyLog.debug(TAG, "Couldn't send data with IOException: " + e.toString());
                mRequestCount.getAndDecrement();
                if (this.getPersistence() != null) {
                    HockeyLog.debug(TAG, "Persisting because of IOException: We're probably offline.");
                    this.getPersistence().makeAvailable(file); //send again later
                }
            }
        }
    }

    /**
     * Read the contents of a file from the persistence layer.
     *
     * @param file The file to read.
     * @return Persisted data as String, or null if the persistence is not set or the file does not exist.
     */
    protected String loadData(File file) {
        String persistedData = null;

        if (this.getPersistence() != null) {
            if (file != null) {
                persistedData = this.getPersistence().load(file);
                if ((persistedData != null) && (persistedData.isEmpty())) {
                    this.getPersistence().deleteFile(file);
                }
            }
        }

        return persistedData;
    }

    /**
     * Create a connection to the default or user defined server endpoint. In case creating a
     * custom URL for the connection fails, a connection to the default endpoint will be created.
     *
     * @return connection to the API endpoint
     */
    @SuppressWarnings("ConstantConditions")
    protected HttpURLConnection createConnection() {
        URL url;
        HttpURLConnection connection = null;
        try {
            if (getCustomServerURL() == null) {
                url = new URL(DEFAULT_ENDPOINT_URL);
            } else {
                url = new URL(this.mCustomServerURL);
                if (url == null) {
                    url = new URL(DEFAULT_ENDPOINT_URL);
                }
            }

            connection = (HttpURLConnection) url.openConnection();
            connection.setReadTimeout(DEFAULT_SENDER_READ_TIMEOUT);
            connection.setConnectTimeout(DEFAULT_SENDER_CONNECT_TIMEOUT);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-json-stream");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
        } catch (IOException e) {
            Log.e(TAG, "Could not open connection for provided URL with exception: ", e);
        }
        return connection;
    }

    /**
     * Callback for the http response from the sender.
     *
     * @param connection   The connection containing the response.
     * @param responseCode The response code from the connection.
     * @param payload      the payload which generated this response
     * @param fileToSend   reference to the file we want to send
     */
    protected void onResponse(HttpURLConnection connection, int responseCode, String payload, File
            fileToSend) {
        mRequestCount.getAndDecrement();
        HockeyLog.debug(TAG, "response code " + Integer.toString(responseCode));

        boolean isRecoverableError = isRecoverableError(responseCode);
        if (isRecoverableError) {
            HockeyLog.debug(TAG, "Recoverable error (probably a server error), persisting data:\n" + payload);
            if (this.getPersistence() != null) {
                this.getPersistence().makeAvailable(fileToSend);
            }
        } else {
            //delete in case of success or unrecoverable errors
            if (this.getPersistence() != null) {
                this.getPersistence().deleteFile(fileToSend);
            }

            //trigger send next file or log unexpected responses
            StringBuilder builder = new StringBuilder();
            if (isExpected(responseCode)) {
                this.readResponse(connection, builder);
                triggerSending();
            } else {
                this.onUnexpected(connection, responseCode, builder);
            }
        }
    }

    /**
     * Determines if an HTTP response code denotes an error from which we can recover by sending the data again.
     *
     * @param responseCode The response code to check.
     * @return True, if we can recover from this error code, otherwise false.
     */
    protected boolean isRecoverableError(int responseCode) {
        /*
            429 -> TOO MANY REQUESTS
            503 -> SERVICE UNAVAILABLE
            511 -> NETWORK AUTHENTICATION REQUIRED
            All not available in HttpUrlConnection, thus listed here for reference.
         */
        List<Integer> recoverableCodes = Arrays.asList(HttpURLConnection.HTTP_CLIENT_TIMEOUT, 429, HttpURLConnection.HTTP_INTERNAL_ERROR, 503, 511);
        return recoverableCodes.contains(responseCode);
    }

    /**
     * Determines if an HTTP response code denotes a status which we regard as successful completion.
     *
     * @param responseCode The response code to check.
     * @return True, if the response code means a successful operation, otherwise false.
     */
    protected boolean isExpected(int responseCode) {
        return (HttpURLConnection.HTTP_OK <= responseCode && responseCode <= HttpURLConnection.HTTP_NOT_AUTHORITATIVE);
    }

    /**
     * Handler to be called if an unexpected response was returned from the ingestion endpoint.
     *
     * @param connection   The connection containing the response.
     * @param responseCode The response code from the connection.
     * @param builder      A string builder for storing the response.
     */
    protected void onUnexpected(HttpURLConnection connection, int responseCode, StringBuilder
            builder) {
        String message = String.format(Locale.ROOT, "Unexpected response code: %d", responseCode);
        builder.append(message);
        builder.append("\n");

        // log the unexpected response
        HockeyLog.debug(TAG, message);

        // attempt to read the response stream
        this.readResponse(connection, builder);
    }


    /**
     * Log information about request/connection/payload to LogCat
     *
     * @param connection the connection
     * @param payload    the payload of telemetry data
     */
    private void logRequest(HttpURLConnection connection, String payload) {
        Writer writer = null;
        try {
            if ((connection != null) && (payload != null)) {
                HockeyLog.debug(TAG, "Sending payload:\n" + payload);
                HockeyLog.debug(TAG, "Using URL:" + connection.getURL().toString());
                writer = getWriter(connection);
                writer.write(payload);
                writer.flush();
            }
        } catch (IOException e) {
            HockeyLog.debug(TAG, "Couldn't log data with: " + e.toString());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    HockeyLog.error(TAG, "Couldn't close writer with: " + e.toString());
                }
            }
        }
    }

    /**
     * Reads the response from a connection.
     *
     * @param connection the connection which will read the response
     * @param builder    a string builder for storing the response
     */
    protected void readResponse(HttpURLConnection connection, StringBuilder builder) {
        BufferedReader reader = null;
        try {
            InputStream inputStream = connection.getErrorStream();
            if (inputStream == null) {
                inputStream = connection.getInputStream();
            }

            if (inputStream != null) {
                InputStreamReader streamReader = new InputStreamReader(inputStream, "UTF-8");
                reader = new BufferedReader(streamReader);
                String responseLine = reader.readLine();
                while (responseLine != null) {
                    builder.append(responseLine);
                    responseLine = reader.readLine();
                }
            } else {
                builder.append(connection.getResponseMessage());
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, e.toString());
                }
            }
        }
    }

    /**
     * Gets a writer from the connection stream (allows for test hooks into the write stream)
     *
     * @param connection the connection to which the stream will be flushed
     * @return a writer for the given connection stream
     * @throws java.io.IOException Exception thrown by GZIP (used in SDK 19+)
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    protected Writer getWriter(HttpURLConnection connection) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // GZIP if we are running SDK 19 or higher
            connection.addRequestProperty("Content-Encoding", "gzip");
            connection.setRequestProperty("Content-Type", "application/x-json-stream");
            GZIPOutputStream gzip = new GZIPOutputStream(connection.getOutputStream(), true);
            return new OutputStreamWriter(gzip, "UTF-8");
        } else {
            // no GZIP for older devices
            return new OutputStreamWriter(connection.getOutputStream(), "UTF-8");
        }
    }

    protected Persistence getPersistence() {
        Persistence persistence = null;
        if (mWeakPersistence != null) {
            persistence = mWeakPersistence.get();
        }
        return persistence;
    }

    /**
     * Set persistence used to reserve, free, or delete files (enables dependency injection).
     *
     * @param persistence a persistence used to reserve, free, or delete files
     */
    protected void setPersistence(Persistence persistence) {
        mWeakPersistence = new WeakReference<>(persistence);
    }

    /**
     * Getter for requestCount. Important for unit testing.
     *
     * @return the number of running requests
     */
    protected int requestCount() {
        return mRequestCount.get();
    }

    protected String getCustomServerURL() {
        return mCustomServerURL;
    }

    /**
     * Set a custom server URL that will be used to send data to it.
     *
     * @param customServerURL URL for custom server endpoint to collect telemetry
     */
    protected void setCustomServerURL(String customServerURL) {
        mCustomServerURL = customServerURL;
    }
}
