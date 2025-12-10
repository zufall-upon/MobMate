package Whisper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.json.JSON;
import org.json.JSONObject;

public class RemoteWhisperCPP {
    private static final String BOUNDARY = "boundary" + System.currentTimeMillis();
    private static final String LINE_FEED = "\r\n";
    private static final String TWO_HYPHENS = "--";
    private final String requestURL;

    public RemoteWhisperCPP(String url) {
        this.requestURL = url;
    }

    public String transcribe(File file, double temperature, double temperatureInc) throws IOException {

        HttpURLConnection connection = null;
        try {
            // Create connection
            URL url = new URL(this.requestURL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

            // Create output stream
            OutputStream outputStream = connection.getOutputStream();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true);

            // Add file part

            writer.append(TWO_HYPHENS).append(BOUNDARY).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.getName()).append("\"").append(LINE_FEED);
            writer.append("Content-Type: ").append(Files.probeContentType(file.toPath())).append(LINE_FEED);
            writer.append(LINE_FEED);
            writer.flush();

            FileInputStream inputStream = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            inputStream.close();
            writer.append(LINE_FEED);

            // Add temperature parameter
            writer.append(TWO_HYPHENS).append(BOUNDARY).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"temperature\"").append(LINE_FEED).append(LINE_FEED);
            writer.append(String.valueOf(temperature)).append(LINE_FEED);

            // Add temperature_inc parameter
            writer.append(TWO_HYPHENS).append(BOUNDARY).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"temperature_inc\"").append(LINE_FEED).append(LINE_FEED);
            writer.append(String.valueOf(temperatureInc)).append(LINE_FEED);

            // Add response_format parameter
            writer.append(TWO_HYPHENS).append(BOUNDARY).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"response_format\"").append(LINE_FEED).append(LINE_FEED);
            writer.append("json").append(LINE_FEED);

            // End of multipart/form-data
            writer.append(TWO_HYPHENS).append(BOUNDARY).append(TWO_HYPHENS).append(LINE_FEED);
            writer.flush();

            // Get Response
            int responseCode = connection.getResponseCode();
            InputStream responseStream = (responseCode >= 400) ? connection.getErrorStream() : connection.getInputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            JSONObject obj = (JSONObject) JSON.parse(response.toString());
            System.out.println("Response: " + response);

            return obj.optString("text", "").trim();

        } catch (Exception ex) {
            throw new IOException("Cannot connect to " + this.requestURL + " (" + ex.getMessage() + ")");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // Example usage
    public static void main(String[] args) {
        String requestURL = "http://127.0.0.1:9595/inference";
        try {
            long t1 = System.currentTimeMillis();
            RemoteWhisperCPP w = new RemoteWhisperCPP(requestURL);

            String response = w.transcribe(new File("rec_1134105638153185080720250906_194401.wav"), 0.0, 0.2);
            System.out.println("Response: " + response);
            long t2 = System.currentTimeMillis();
            System.out.println("Response  " + (t2 - t1) + " ms");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
