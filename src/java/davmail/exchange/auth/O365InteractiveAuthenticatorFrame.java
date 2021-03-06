/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package davmail.exchange.auth;

import davmail.BundleMessage;
import davmail.http.DavGatewayHttpClientFacade;
import davmail.ui.tray.DavGatewayTray;
import davmail.util.IOUtil;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebErrorEvent;
import javafx.scene.web.WebEvent;
import javafx.scene.web.WebView;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;

import javax.swing.*;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.List;
import java.util.Map;

public class O365InteractiveAuthenticatorFrame extends JFrame {
    private static final Logger LOGGER = Logger.getLogger(O365InteractiveAuthenticatorFrame.class);

    private O365InteractiveAuthenticator authenticator;

    static {
        // register a stream handler for msauth protocol
        try {
            URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
                @Override
                public URLStreamHandler createURLStreamHandler(String protocol) {
                    if ("msauth".equals(protocol) || "urn".equals(protocol)) {
                        return new URLStreamHandler() {
                            @Override
                            protected URLConnection openConnection(URL u) {
                                return new URLConnection(u) {
                                    @Override
                                    public void connect() {
                                        // ignore
                                    }
                                };
                            }
                        };
                    } else if ("https".equals(protocol)) {
                        return new sun.net.www.protocol.https.Handler() {
                            @Override
                            protected URLConnection openConnection(URL url) throws IOException {
                                return openConnection(url, null);
                            }

                            @Override
                            protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
                                LOGGER.debug("openConnection " + url);

                                if (url.toExternalForm().endsWith("/common/handlers/watson")) {
                                    LOGGER.warn("Failed: form calls watson");
                                }
                                final HttpURLConnection httpsURLConnection = (HttpURLConnection) super.openConnection(url, proxy);

                                if (("login.microsoftonline.com".equals(url.getHost()) && "/common/oauth2/authorize".equals(url.getPath()))
                                        || ("login.live.com".equals(url.getHost()) && "/oauth20_authorize.srf".equals(url.getPath()))
                                        || ("login.live.com".equals(url.getHost()) && "/ppsecure/post.srf".equals(url.getPath()))
                                        || ("login.microsoftonline.com".equals(url.getHost()) && "/login.srf".equals(url.getPath()))
                                        || ("login.microsoftonline.com".equals(url.getHost()) && "/common/login".equals(url.getPath()))
                                        || ("login.microsoftonline.com".equals(url.getHost()) && "/common/SAS/ProcessAuth".equals(url.getPath()))
                                        // Okta authentication form
                                        || (url.getHost().endsWith(".okta.com") &&
                                        ("/login/login.htm".equals(url.getPath())
                                                || "/auth/services/devicefingerprint".equals(url.getPath())))
                                ) {
                                    LOGGER.debug("Disable integrity check on external resources at " + url);

                                    return new HttpURLConnection(url) {
                                        @Override
                                        public void setRequestMethod(String method) throws ProtocolException {
                                            httpsURLConnection.setRequestMethod(method);
                                        }

                                        @Override
                                        public void setInstanceFollowRedirects(boolean followRedirects) {
                                            httpsURLConnection.setInstanceFollowRedirects(followRedirects);
                                        }

                                        @Override
                                        public boolean getInstanceFollowRedirects() {
                                            return httpsURLConnection.getInstanceFollowRedirects();
                                        }

                                        @Override
                                        public String getRequestMethod() {
                                            return httpsURLConnection.getRequestMethod();
                                        }

                                        @Override
                                        public int getResponseCode() throws IOException {
                                            return httpsURLConnection.getResponseCode();
                                        }

                                        @Override
                                        public String getResponseMessage() throws IOException {
                                            return httpsURLConnection.getResponseMessage();
                                        }

                                        @Override
                                        public Map<String, List<String>> getHeaderFields() {
                                            LOGGER.debug(httpsURLConnection.getHeaderFields());
                                            return httpsURLConnection.getHeaderFields();
                                        }

                                        @Override
                                        public String getHeaderField(String name) {
                                            return httpsURLConnection.getHeaderField(name);
                                        }

                                        @Override
                                        public String getHeaderField(int n) {
                                            return httpsURLConnection.getHeaderField(n);
                                        }

                                        @Override
                                        public void disconnect() {
                                            httpsURLConnection.disconnect();
                                        }

                                        @Override
                                        public void setDoOutput(boolean dooutput) {
                                            httpsURLConnection.setDoOutput(dooutput);
                                        }

                                        @Override
                                        public boolean usingProxy() {
                                            return httpsURLConnection.usingProxy();
                                        }

                                        @Override
                                        public void connect() throws IOException {
                                            try {
                                                httpsURLConnection.connect();
                                            } catch (IOException e) {
                                                LOGGER.error(e.getMessage());
                                                throw e;
                                            }
                                        }

                                        @Override
                                        public InputStream getInputStream() throws IOException {
                                            byte[] content = IOUtil.readFully(httpsURLConnection.getInputStream());
                                            String contentAsString = new String(content, "UTF-8")
                                                    .replaceAll("integrity ?=", "integrity.disabled=");
                                            LOGGER.debug(contentAsString);
                                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                            baos.write(contentAsString.getBytes("UTF-8"));
                                            return new ByteArrayInputStream(baos.toByteArray());
                                        }

                                        @Override
                                        public OutputStream getOutputStream() throws IOException {
                                            return httpsURLConnection.getOutputStream();
                                        }

                                        @Override
                                        public InputStream getErrorStream() {
                                            return httpsURLConnection.getErrorStream();
                                        }

                                    };

                                } else {
                                    return httpsURLConnection;
                                }
                            }

                        };
                    }
                    return null;
                }
            });
        } catch (Throwable t) {
            LOGGER.warn("Unable to register protocol handler");
        }
    }

    String location;
    final JFXPanel fxPanel = new JFXPanel();

    public O365InteractiveAuthenticatorFrame() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!authenticator.isAuthenticated && authenticator.errorCode == null) {
                    authenticator.errorCode = "user closed authentication window";
                }
            }
        });

        setTitle(BundleMessage.format("UI_DAVMAIL_GATEWAY"));
        try {
            setIconImages(DavGatewayTray.getFrameIcons());
        } catch (NoSuchMethodError error) {
            DavGatewayTray.debug(new BundleMessage("LOG_UNABLE_TO_SET_ICON_IMAGE"));
        }

        JPanel mainPanel = new JPanel();

        mainPanel.add(fxPanel);
        add(BorderLayout.CENTER, mainPanel);

        pack();
        setResizable(true);
        // center frame
        setSize(600, 600);
        setLocationRelativeTo(null);
        setVisible(true);
        // bring window to top
        setAlwaysOnTop(true);
        setAlwaysOnTop(false);
    }

    public void setO365InteractiveAuthenticator(O365InteractiveAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    private void initFX(final JFXPanel fxPanel, final String url, final String redirectUri) {
        WebView webView = new WebView();
        final WebEngine webViewEngine = webView.getEngine();

        final ProgressBar loadProgress = new ProgressBar();
        loadProgress.progressProperty().bind(webViewEngine.getLoadWorker().progressProperty());

        StackPane hBox = new StackPane();
        hBox.getChildren().setAll(webView, loadProgress);
        Scene scene = new Scene(hBox);
        fxPanel.setScene(scene);

        webViewEngine.setUserAgent(DavGatewayHttpClientFacade.getUserAgent());
        webViewEngine.setOnAlert(new EventHandler<WebEvent<String>>() {
            @Override
            public void handle(final WebEvent<String> stringWebEvent) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        String message = stringWebEvent.getData();
                        JOptionPane.showMessageDialog(O365InteractiveAuthenticatorFrame.this, message);
                    }
                });
            }
        });
        webViewEngine.setOnError(new EventHandler<WebErrorEvent>() {
            @Override
            public void handle(WebErrorEvent event) {
                LOGGER.error(event.getMessage());
            }
        });


        webViewEngine.getLoadWorker().stateProperty().addListener(new ChangeListener<Worker.State>() {
            @Override
            public void changed(ObservableValue ov, Worker.State oldState, Worker.State newState) {
                if (newState == Worker.State.SUCCEEDED) {
                    loadProgress.setVisible(false);
                    location = webViewEngine.getLocation();
                    updateTitleAndFocus(location);
                    LOGGER.debug("Webview location: " + location);
                    // override console.log
                    O365InteractiveJSLogger.register(webViewEngine);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(dumpDocument(webViewEngine.getDocument()));
                    }
                    if (location.startsWith(redirectUri)) {
                        LOGGER.debug("Location starts with redirectUri, check code");

                        authenticator.isAuthenticated = location.contains("code=") && location.contains("&session_state=");
                        if (!authenticator.isAuthenticated && location.contains("error=")) {
                            authenticator.errorCode = location.substring(location.indexOf("error="));
                        }
                        if (authenticator.isAuthenticated) {
                            LOGGER.debug("Authenticated location: " + location);
                            String code = location.substring(location.indexOf("code=") + 5, location.indexOf("&session_state="));
                            String sessionState = location.substring(location.lastIndexOf('='));

                            LOGGER.debug("Authentication Code: " + code);
                            LOGGER.debug("Authentication session state: " + sessionState);
                            authenticator.code = code;
                        }
                        close();
                    }
                } else if (newState == Worker.State.FAILED) {
                    Throwable e = webViewEngine.getLoadWorker().getException();
                    if (e != null) {
                        handleError(e);
                    }
                    close();
                }

            }

        });
        webViewEngine.load(url);
    }

    private void updateTitleAndFocus(final String location) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                setState(JFrame.NORMAL);
                setAlwaysOnTop(true);
                setAlwaysOnTop(false);
                setTitle("DavMail: " + location);
            }
        });
    }

    public String dumpDocument(Document document) {
        String result;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

            transformer.transform(new DOMSource(document),
                    new StreamResult(new OutputStreamWriter(baos, "UTF-8")));
            result = baos.toString("UTF-8");
        } catch (Exception e) {
            result = e + " " + e.getMessage();
        }
        return result;
    }

    public void authenticate(final String initUrl, final String redirectUri) {
        // Run initFX as JavaFX-Thread
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                try {
                    Platform.setImplicitExit(false);

                    initFX(fxPanel, initUrl, redirectUri);
                } catch (Throwable t) {
                    handleError(t);
                    close();
                }
            }
        });
    }

    public void handleError(Throwable t) {
        LOGGER.error(t + " " + t.getMessage());
        authenticator.errorCode = t.getMessage();
        if (authenticator.errorCode == null) {
            authenticator.errorCode = t.toString();
        }
    }

    public void close() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                setVisible(false);
                dispose();
            }
        });
    }

}
