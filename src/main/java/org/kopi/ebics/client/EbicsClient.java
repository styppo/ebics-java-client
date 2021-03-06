/*
 * Copyright (c) 1990-2012 kopiLeft Development SARL, Bizerte, Tunisia
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License version 2.1 as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 * $Id$
 */

package org.kopi.ebics.client;

import org.apache.commons.cli.*;
import org.apache.log4j.Level;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.kopi.ebics.exception.EbicsException;
import org.kopi.ebics.exception.NoDownloadDataAvailableException;
import org.kopi.ebics.interfaces.*;
import org.kopi.ebics.io.IOUtils;
import org.kopi.ebics.messages.Messages;
import org.kopi.ebics.schema.h003.OrderAttributeType;
import org.kopi.ebics.session.DefaultConfiguration;
import org.kopi.ebics.session.EbicsSession;
import org.kopi.ebics.session.OrderType;
import org.kopi.ebics.session.Product;
import org.kopi.ebics.utils.Constants;

import java.io.*;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;

/**
 * The ebics client application. Performs necessary tasks to contact the ebics
 * bank server like sending the INI, HIA and HPB requests for keys retrieval and
 * also performs the files transfer including uploads and downloads.
 *
 */
public class EbicsClient {

    private final Configuration configuration;
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, Partner> partners = new HashMap<>();
    private final Map<String, Bank> banks = new HashMap<>();
    private final ConfigProperties properties;
    private Product defaultProduct;
    private User defaultUser;

    static {
        org.apache.xml.security.Init.init();
        java.security.Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Constructs a new ebics client application
     *
     * @param configuration
     *            the application configuration
     * @param properties
     */
    public EbicsClient(Configuration configuration, ConfigProperties properties) {
        this.configuration = configuration;
        this.properties = properties;
        Messages.setLocale(configuration.getLocale());
        configuration.init();
        configuration.getLogger().info(
            Messages.getString("init.configuration", Constants.APPLICATION_BUNDLE_NAME));
    }

    private EbicsSession createSession(User user, Product product) {
        EbicsSession session = new EbicsSession(user, configuration);
        session.setProduct(product);
        return session;
    }

    /**
     * Creates the user necessary directories
     *
     * @param user
     *            the concerned user
     */
    public void createUserDirectories(EbicsUser user) {
        configuration.getLogger().info(
            Messages.getString("user.create.directories", Constants.APPLICATION_BUNDLE_NAME,
                user.getUserId()));
        IOUtils.createDirectories(configuration.getUserDirectory(user.getUserId()));
        IOUtils.createDirectories(configuration.getTransferTraceDirectory(user.getUserId()));
        IOUtils.createDirectories(configuration.getKeystoreDirectory(user.getUserId()));
        IOUtils.createDirectories(configuration.getLettersDirectory(user.getUserId()));
    }

    /**
     * Creates a new EBICS bank with the data you should have obtained from the
     * bank.
     *
     * @param url
     *            the bank URL
     * @param url
     *            the bank name
     * @param hostId
     *            the bank host ID
     * @param useCertificate
     *            does the bank use certificates ?
     * @return the created ebics bank
     */
    private Bank createBank(URL url, String name, String hostId, boolean useCertificate) {
        Bank bank = new Bank(url, name, hostId, useCertificate);
        banks.put(hostId, bank);
        return bank;
    }

    /**
     * Creates a new ebics partner
     *
     * @param bank
     *            the bank
     * @param partnerId
     *            the partner ID
     */
    private Partner createPartner(EbicsBank bank, String partnerId) {
        Partner partner = new Partner(bank, partnerId);
        partners.put(partnerId, partner);
        return partner;
    }

    /**
     * Creates a new ebics user and generates its certificates.
     *
     * @param url
     *            the bank url
     * @param bankName
     *            the bank name
     * @param hostId
     *            the bank host ID
     * @param partnerId
     *            the partner ID
     * @param userId
     *            UserId as obtained from the bank.
     * @param name
     *            the user name,
     * @param email
     *            the user email
     * @param country
     *            the user country
     * @param organization
     *            the user organization or company
     * @param useCertificates
     *            does the bank use certificates ?
     * @param saveCertificates
     *            save generated certificates?
     * @param passwordCallback
     *            a callback-handler that supplies us with the password. This
     *            parameter can be null, in this case no password is used.
     * @return
     * @throws IOException
     * @throws GeneralSecurityException
     * @throws EbicsException
     */
    public User createUser(URL url, String bankName, String hostId, String partnerId,
        String userId, String name, String email, String country, String organization,
        boolean useCertificates, boolean saveCertificates, PasswordCallback passwordCallback)
        throws IOException, GeneralSecurityException, EbicsException {
        configuration.getLogger().info(
            Messages.getString("user.create.info", Constants.APPLICATION_BUNDLE_NAME, userId));

        Bank bank = createBank(url, bankName, hostId, useCertificates);
        Partner partner = createPartner(bank, partnerId);
        try {
            User user = new User(partner, userId, name, email, country, organization,
                passwordCallback);
            createUserDirectories(user);
            if (saveCertificates) {
                user.saveUserCertificates(configuration.getKeystoreDirectory(user.getUserId()));
            }
            configuration.getSerializationManager().serialize(bank);
            configuration.getSerializationManager().serialize(partner);
            configuration.getSerializationManager().serialize(user);
            createLetters(user, useCertificates);
            users.put(userId, user);
            partners.put(partner.getPartnerId(), partner);
            banks.put(bank.getHostId(), bank);

            configuration.getLogger().info(
                Messages.getString("user.create.success", Constants.APPLICATION_BUNDLE_NAME, userId));
            return user;
        } catch (IOException | GeneralSecurityException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("user.create.error", Constants.APPLICATION_BUNDLE_NAME), e);
            throw e;
        }
    }

    private void createLetters(EbicsUser user, boolean useCertificates)
        throws IOException, GeneralSecurityException, EbicsException {
        user.getPartner().getBank().setUseCertificate(useCertificates);
        LetterManager letterManager = configuration.getLetterManager();
        List<InitLetter> letters = Arrays.asList(letterManager.createA005Letter(user),
            letterManager.createE002Letter(user), letterManager.createX002Letter(user));

        File directory = new File(configuration.getLettersDirectory(user.getUserId()));
        for (InitLetter letter : letters) {
            try (FileOutputStream out = new FileOutputStream(new File(directory, letter.getName()))) {
                letter.writeTo(out);
            }
        }
    }

    /**
     * Loads a user knowing its ID
     *
     * @throws IOException
     * @throws GeneralSecurityException
     * @throws ClassNotFoundException
     * @throws EbicsException
     */
    public User loadUser(String hostId, String partnerId, String userId,
        PasswordCallback passwordCallback)
        throws IOException, GeneralSecurityException, ClassNotFoundException, EbicsException {
        configuration.getLogger().info(
            Messages.getString("user.load.info", Constants.APPLICATION_BUNDLE_NAME, userId));

        try {
            Bank bank;
            Partner partner;
            User user;
            try (ObjectInputStream input = configuration.getSerializationManager().deserialize(
                hostId)) {
                bank = (Bank) input.readObject();
            }
            try (ObjectInputStream input = configuration.getSerializationManager().deserialize(
                "partner-" + partnerId)) {
                partner = new Partner(bank, input);
            }
            try (ObjectInputStream input = configuration.getSerializationManager().deserialize(
                "user-" + userId)) {
                user = new User(partner, input, configuration.getKeystoreDirectory(userId), passwordCallback);
            }
            users.put(userId, user);
            partners.put(partner.getPartnerId(), partner);
            banks.put(bank.getHostId(), bank);
            configuration.getLogger().info(
                Messages.getString("user.load.success", Constants.APPLICATION_BUNDLE_NAME, userId));
            return user;
        } catch (IOException | GeneralSecurityException | ClassNotFoundException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("user.load.error", Constants.APPLICATION_BUNDLE_NAME), e);
            throw e;
        }
    }

    /**
     * Sends an INI request to the ebics bank server
     *
     * @param user
     *            the user
     * @param product
     *            the application product
     * @throws IOException
     * @throws EbicsException
     */
    public void sendINIRequest(User user, Product product) throws IOException, EbicsException {
        String userId = user.getUserId();
        configuration.getLogger().info(
            Messages.getString("ini.request.send", Constants.APPLICATION_BUNDLE_NAME, userId));
        if (user.isInitialized()) {
            configuration.getLogger().info(
                Messages.getString("user.already.initialized", Constants.APPLICATION_BUNDLE_NAME,
                    userId));
            return;
        }
        EbicsSession session = createSession(user, product);
        KeyManagement keyManager = new KeyManagement(session);
        configuration.getTraceManager().setTraceDirectory(
            configuration.getTransferTraceDirectory(user.getUserId()));
        try {
            keyManager.sendINI(null);
            user.setInitialized(true);
            configuration.getLogger().info(
                Messages.getString("ini.send.success", Constants.APPLICATION_BUNDLE_NAME, userId));
        } catch (IOException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("ini.send.error", Constants.APPLICATION_BUNDLE_NAME, userId), e);
            throw e;
        }
    }

    /**
     * Sends a HIA request to the ebics server.
     *
     * @param user
     *            the user
     * @param product
     *            the application product
     * @throws IOException
     * @throws EbicsException
     */
    public void sendHIARequest(User user, Product product) throws IOException, EbicsException {
        String userId = user.getUserId();
        configuration.getLogger().info(
            Messages.getString("hia.request.send", Constants.APPLICATION_BUNDLE_NAME, userId));
        if (user.isInitializedHIA()) {
            configuration.getLogger().info(
                Messages.getString("user.already.hia.initialized",
                    Constants.APPLICATION_BUNDLE_NAME, userId));
            return;
        }
        EbicsSession session = createSession(user, product);
        KeyManagement keyManager = new KeyManagement(session);
        configuration.getTraceManager().setTraceDirectory(
            configuration.getTransferTraceDirectory(user.getUserId()));
        try {
            keyManager.sendHIA(null);
            user.setInitializedHIA(true);
        } catch (IOException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("hia.send.error", Constants.APPLICATION_BUNDLE_NAME, userId), e);
            throw e;
        }
        configuration.getLogger().info(
            Messages.getString("hia.send.success", Constants.APPLICATION_BUNDLE_NAME, userId));
    }

    /**
     * Sends a HPB request to the ebics server.
     */
    public void sendHPBRequest(User user, Product product) throws IOException, GeneralSecurityException, EbicsException {
        String userId = user.getUserId();
        configuration.getLogger().info(
            Messages.getString("hpb.request.send", Constants.APPLICATION_BUNDLE_NAME, userId));

        EbicsSession session = createSession(user, product);
        KeyManagement keyManager = new KeyManagement(session);

        configuration.getTraceManager().setTraceDirectory(
            configuration.getTransferTraceDirectory(user.getUserId()));

        try {
            keyManager.sendHPB();
            configuration.getLogger().info(
                Messages.getString("hpb.send.success", Constants.APPLICATION_BUNDLE_NAME, userId));
        } catch (IOException | GeneralSecurityException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("hpb.send.error", Constants.APPLICATION_BUNDLE_NAME, userId), e);
            throw e;
        }
    }

    /**
     * Sends the SPR order to the bank.
     *
     * @param user
     *            the user
     * @param product
     *            the application product
     * @throws IOException
     * @throws EbicsException
     */
    public void revokeSubscriber(User user, Product product) throws IOException, EbicsException {
        String userId = user.getUserId();

        configuration.getLogger().info(
            Messages.getString("spr.request.send", Constants.APPLICATION_BUNDLE_NAME, userId));

        EbicsSession session = createSession(user, product);
        KeyManagement keyManager = new KeyManagement(session);

        configuration.getTraceManager().setTraceDirectory(
            configuration.getTransferTraceDirectory(user.getUserId()));

        try {
            keyManager.lockAccess();
        } catch (IOException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("spr.send.error", Constants.APPLICATION_BUNDLE_NAME, userId), e);
            throw e;
        }

        configuration.getLogger().info(
            Messages.getString("spr.send.success", Constants.APPLICATION_BUNDLE_NAME, userId));
    }

    /**
     * Sends a file to the ebics bank server
     * @throws IOException
     * @throws EbicsException
     */
    public void sendFile(File file, OrderType orderType) throws IOException, EbicsException {
        try (final InputStream input = new FileInputStream(file)) {
            sendFile(input, defaultUser, defaultProduct, orderType);
        }
    }

    public void sendFile(InputStream input, OrderType orderType) throws IOException, EbicsException {
        sendFile(input, this.defaultUser, this.defaultProduct, orderType);
    }

    public void sendFile(InputStream input, OrderType orderType, Integer orderId) throws IOException, EbicsException {
        sendFile(input, this.defaultUser, this.defaultProduct, orderType, orderId);
    }

    public void sendFile(InputStream input, User user, Product product, OrderType orderType) throws IOException, EbicsException {
        sendFile(input, user, product, orderType, null);
    }

    public void sendFile(InputStream input, User user, Product product, OrderType orderType, Integer orderId) throws IOException, EbicsException {
        EbicsSession session = createSession(user, product);
        OrderAttributeType.Enum orderAttribute = OrderAttributeType.OZHNN;

        FileTransfer transferManager = new FileTransfer(session);

        configuration.getTraceManager().setTraceDirectory(
            configuration.getTransferTraceDirectory(user.getUserId()));

        try {
            transferManager.sendFile(IOUtils.inputStreamToBytes(input), orderType, orderAttribute, orderId);
        } catch (IOException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("upload.file.error", Constants.APPLICATION_BUNDLE_NAME), e);
            throw e;
        }
    }

    public void fetchFile(OutputStream output, User user, Product product, OrderType orderType,
        boolean isTest, Date start, Date end) throws IOException, EbicsException {
        FileTransfer transferManager;
        EbicsSession session = createSession(user, product);
        session.addSessionParam("FORMAT", "pain.xxx.cfonb160.dct");
        if (isTest) {
            session.addSessionParam("TEST", "true");
        }
        transferManager = new FileTransfer(session);

        configuration.getTraceManager().setTraceDirectory(
            configuration.getTransferTraceDirectory(user.getUserId()));

        try {
            transferManager.fetchFile(orderType, start, end, output);
        } catch (NoDownloadDataAvailableException e) {
            // don't log this exception as an error, caller can decide how to handle
            throw e;
        } catch (IOException | EbicsException e) {
            configuration.getLogger().error(
                Messages.getString("download.file.error", Constants.APPLICATION_BUNDLE_NAME), e);
            throw e;
        }
    }

    public void fetchFile(File file, OrderType orderType, Date start, Date end) throws IOException, EbicsException {
        try (final OutputStream out = new FileOutputStream(file)) {
            fetchFile(out, defaultUser, defaultProduct, orderType, false, start, end);
        } catch (EbicsException e) {
            file.delete();
            throw e;
        }
    }

    /**
     * Performs buffers save before quitting the client application.
     */
    public void quit() {
        try {
            for (User user : users.values()) {
                if (user.needsSave()) {
                    configuration.getLogger().info(
                        Messages.getString("app.quit.users", Constants.APPLICATION_BUNDLE_NAME,
                            user.getUserId()));
                    configuration.getSerializationManager().serialize(user);
                }
            }

            for (Partner partner : partners.values()) {
                if (partner.needsSave()) {
                    configuration.getLogger().info(
                        Messages.getString("app.quit.partners", Constants.APPLICATION_BUNDLE_NAME,
                            partner.getPartnerId()));
                    configuration.getSerializationManager().serialize(partner);
                }
            }

            for (Bank bank : banks.values()) {
                if (bank.needsSave()) {
                    configuration.getLogger().info(
                        Messages.getString("app.quit.banks", Constants.APPLICATION_BUNDLE_NAME,
                            bank.getHostId()));
                    configuration.getSerializationManager().serialize(bank);
                }
            }
        } catch (EbicsException e) {
            configuration.getLogger().info(
                Messages.getString("app.quit.error", Constants.APPLICATION_BUNDLE_NAME));
        }

        clearTraces();
    }

    public void clearTraces() {
        configuration.getLogger().info(
            Messages.getString("app.cache.clear", Constants.APPLICATION_BUNDLE_NAME));
        configuration.getTraceManager().clear();
    }

    public static class ConfigProperties {
        Properties properties = new Properties();

        public ConfigProperties(File file) throws FileNotFoundException, IOException {
            properties.load(new FileInputStream(file));
        }

        public ConfigProperties(Properties properties) {
            this.properties = properties;
        }

        public String get(String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("property not set or emtpy" + key);
            }
            return value.trim();
        }

        public String get(String key, String defaultValue) {
            String value = properties.getProperty(key);
            if (value == null || value.isEmpty()) {
                return defaultValue;
            }
            return value.trim();
        }
    }

    private User createUser(ConfigProperties properties, PasswordCallback pwdHandler)
        throws IOException, GeneralSecurityException, EbicsException {
        String userId = properties.get("userId");
        String partnerId = properties.get("partnerId");
        String bankUrl = properties.get("bank.url");
        String bankName = properties.get("bank.name");
        String hostId = properties.get("hostId");
        String userName = properties.get("user.name");
        String userEmail = properties.get("user.email");
        String userCountry = properties.get("user.country");
        String userOrg = properties.get("user.org");
        boolean useCertificates = false;
        boolean saveCertificates = true;
        return createUser(new URL(bankUrl), bankName, hostId, partnerId, userId, userName, userEmail,
            userCountry, userOrg, useCertificates, saveCertificates, pwdHandler);
    }

    private static CommandLine parseArguments(Options options, String[] args) throws ParseException {
        CommandLineParser parser = new DefaultParser();
        options.addOption(null, "help", false, "Print this help text");
        CommandLine line = parser.parse(options, args);
        if (line.hasOption("help")) {
            HelpFormatter formatter = new HelpFormatter();
            System.out.println();
            formatter.printHelp(EbicsClient.class.getSimpleName(), options);
            System.out.println();
            System.exit(0);
        }
        return line;
    }

    public static EbicsClient createEbicsClient(File rootDir, File configFile) throws IOException {
        return createEbicsClient(rootDir, new ConfigProperties(configFile));
    }

    public static EbicsClient createEbicsClient(File rootDir, Properties properties) {
        return createEbicsClient(rootDir, new ConfigProperties(properties));
    }

    private static EbicsClient createEbicsClient(File rootDir, ConfigProperties properties) {
        final String country = properties.get("countryCode", "DE").toUpperCase();
        final String language = properties.get("languageCode", "de").toLowerCase();
        final String productName = properties.get("productName");

        final Locale locale = new Locale(language, country);
        final boolean logFileEnabled = Boolean.parseBoolean(properties.get("log.file.enabled", "true"));
        final Level logLevel = Level.toLevel(properties.get("log.level", "ALL"), Level.ALL);

        DefaultConfiguration configuration = new DefaultConfiguration(rootDir.getAbsolutePath()) {
            @Override
            public Locale getLocale() {
                return locale;
            }

            @Override
            public boolean isLogFileEnabled() {
                return logFileEnabled;
            }

            @Override
            public Level getLogLevel() {
                return logLevel;
            }
        };


        EbicsClient client = new EbicsClient(configuration, properties);

        Product product = new Product(productName, language, null);

        client.setDefaultProduct(product);

        return client;
    }

    public void createDefaultUser() throws IOException, GeneralSecurityException, EbicsException {
        defaultUser = createUser(properties, createPasswordCallback());
    }

    public void loadDefaultUser() throws IOException, GeneralSecurityException, ClassNotFoundException, EbicsException {
        String userId = properties.get("userId");
        String hostId = properties.get("hostId");
        String partnerId = properties.get("partnerId");
        defaultUser = loadUser(hostId, partnerId, userId, createPasswordCallback());
    }

    private PasswordCallback createPasswordCallback() {
        final String password = properties.get("password");
        return new PasswordCallback() {
            @Override
            public char[] getPassword() {
                return password.toCharArray();
            }
        };
    }

    public User getDefaultUser() {
        return defaultUser;
    }

    public Product getDefaultProduct() {
        return defaultProduct;
    }

    private void setDefaultProduct(Product product) {
        this.defaultProduct = product;
    }

    private static void addOption(Options options, OrderType type, String description) {
        options.addOption(null, type.name().toLowerCase(), false, description);
    }

    private static boolean hasOption(CommandLine cmd, OrderType type) {
        return cmd.hasOption(type.name().toLowerCase());
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        addOption(options, OrderType.INI, "Send INI request");
        addOption(options, OrderType.HIA, "Send HIA request");
        addOption(options, OrderType.HPB, "Send HPB request");
        options.addOption(null, "letters", false, "Create INI Letters");
        options.addOption(null, "create", false, "Create and initialize EBICS user");
        addOption(options, OrderType.STA,"Fetch STA file (MT940 file)");
        addOption(options, OrderType.VMK, "Fetch VMK file (MT942 file)");
        addOption(options, OrderType.C52, "Fetch camt.052 file");
        addOption(options, OrderType.C53, "Fetch camt.053 file");
        addOption(options, OrderType.C54, "Fetch camt.054 file");
        addOption(options, OrderType.C5N, "Fetch C5N file (zip file with camt.054 documents)");
        addOption(options, OrderType.ZDF, "Fetch ZDF file (zip file with documents)");
        addOption(options, OrderType.ZB6, "Fetch ZB6 file");
        addOption(options, OrderType.PTK, "Fetch client protocol file (TXT)");
        addOption(options, OrderType.HAC, "Fetch client protocol file (XML)");
        addOption(options, OrderType.Z01, "Fetch Z01 file");
        addOption(options, OrderType.CIZ, "Fetch CIZ file");
        addOption(options, OrderType.CRC, "Fetch CRC file");
        addOption(options, OrderType.CRJ, "Fetch CRJ file");
        addOption(options, OrderType.CRZ, "Fetch CRZ file");
        addOption(options, OrderType.HAA, "Fetch HAA file");
        addOption(options, OrderType.HTD, "Fetch HTD file");

        addOption(options, OrderType.XKD, "Send payment order file (DTA format)");
        addOption(options, OrderType.FUL, "Send payment order file (any format)");
        addOption(options, OrderType.XCT, "Send XCT file (any format)");
        addOption(options, OrderType.XE2, "Send XE2 file (any format)");
        addOption(options, OrderType.CCT, "Send CCT file (any format)");
        addOption(options, OrderType.CIP, "Send CIP file (any format)");

        options.addOption(null, "skip_order", true, "Skip a number of order ids");

        options.addOption("o", "output", true, "Output file");
        options.addOption("i", "input", true, "Input file");

        options.addOption("s", "start", true, "Start date");
        options.addOption("e", "end", true, "End date");

        CommandLine cmd = parseArguments(options, args);

        File defaultRootDir = new File(System.getProperty("user.home") + File.separator + "ebics"
            + File.separator + "client");
        File ebicsClientProperties = new File(defaultRootDir, "ebics.txt");
        EbicsClient client = createEbicsClient(defaultRootDir, ebicsClientProperties);

        if (cmd.hasOption("create")) {
            client.createDefaultUser();
        } else {
            client.loadDefaultUser();
        }

        if (cmd.hasOption("letters")) {
            client.createLetters(client.defaultUser, false);
        }

        if (hasOption(cmd, OrderType.INI)) {
            client.sendINIRequest(client.defaultUser, client.defaultProduct);
        }
        if (hasOption(cmd, OrderType.HIA)) {
            client.sendHIARequest(client.defaultUser, client.defaultProduct);
        }
        if (hasOption(cmd, OrderType.HPB)) {
            client.sendHPBRequest(client.defaultUser, client.defaultProduct);
        }

        String outputFileValue = cmd.getOptionValue("o");
        String inputFileValue = cmd.getOptionValue("i");

        String start = cmd.getOptionValue("s");
        String end = cmd.getOptionValue("e");
        Date startDate = null;
        Date endDate = null;
        if (start != null) {
            final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
            startDate = format.parse(start);
            endDate = end != null
                    ? format.parse(end)
                    : Date.from(Instant.now());
        } else if (end != null) {
            throw new EbicsException("Start date required if end date is given");
        }

        List<OrderType> fetchFileOrders = Arrays.asList(OrderType.STA, OrderType.VMK,
            OrderType.C52, OrderType.C53, OrderType.C54, OrderType.C5N, OrderType.CIZ,
            OrderType.ZDF, OrderType.ZB6, OrderType.PTK, OrderType.HAC, OrderType.Z01,
            OrderType.CRC, OrderType.CRJ, OrderType.CRZ, OrderType.HAA, OrderType.HTD);
        for (OrderType type : fetchFileOrders) {
            if (hasOption(cmd, type)) {
                client.fetchFile(getOutputFile(outputFileValue), type, startDate, endDate);
                break;
            }
        }

        List<OrderType> sendFileOrders = Arrays.asList(OrderType.XKD, OrderType.FUL, OrderType.XCT,
            OrderType.XE2, OrderType.CCT, OrderType.CIP);
        for (OrderType type : sendFileOrders) {
            if (hasOption(cmd, type)) {
                client.sendFile(new File(inputFileValue), type);
                break;
            }
        }

        if (cmd.hasOption("skip_order")) {
            int count = Integer.parseInt(cmd.getOptionValue("skip_order"));
            while(count-- > 0) {
                client.defaultUser.getPartner().nextOrderId();
            }
        }

        client.quit();
    }


    private static File getOutputFile(String outputFileName) {
        if (outputFileName == null || outputFileName.isEmpty()) {
            throw new IllegalArgumentException("output file not set");
        }
        File file = new File(outputFileName);
        if (file.exists()) {
            throw new IllegalArgumentException("file already exists " + file);
        }
        return file;
    }
}
