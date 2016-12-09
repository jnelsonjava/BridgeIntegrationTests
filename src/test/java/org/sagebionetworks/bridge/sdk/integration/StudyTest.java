package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.model.*;
import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import org.sagebionetworks.bridge.rest.ClientManager;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.UploadsApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.rest.exceptions.UnsupportedVersionException;
import org.sagebionetworks.client.SynapseAdminClientImpl;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.*;
import org.sagebionetworks.repo.model.util.ModelConstants;
import retrofit2.Call;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class StudyTest {
    
    private TestUser admin;
    private String studyId;
    private SynapseClient synapseClient;
    private Project project;
    private Team team;

    private Properties config;

    private static final String USER_NAME = "synapse.user";
    private static final String SYNAPSE_API_KEY_NAME = "synapse.api.key";
    private static final String EXPORTER_SYNAPSE_USER_ID_NAME = "exporter.synapse.user.id";
    private static final String TEST_USER_ID_NAME = "test.synapse.user.id";

    // synapse related attributes
    private static String SYNAPSE_USER;
    private static String SYNAPSE_API_KEY;
    private static String EXPORTER_SYNAPSE_USER_ID;
    private static Long TEST_USER_ID; // test user exists in synapse
    private static final String USER_CONFIG_FILE = System.getProperty("user.home") + "/bridge-sdk.properties";

    @Before
    public void before() {
        // pre-load test user id and exporter synapse user id
        setupProperties();

        admin = TestUserHelper.getSignedInAdmin();
        synapseClient = new SynapseAdminClientImpl();
        synapseClient.setUserName(SYNAPSE_USER);
        synapseClient.setApiKey(SYNAPSE_API_KEY);
    }

    @After
    public void after() throws Exception {
        if (studyId != null) {
            admin.getClient(StudiesApi.class).deleteStudy(studyId).execute();
        }
        if (project != null) {
            synapseClient.deleteEntityById(project.getId());
        }
        if (team != null) {
            synapseClient.deleteTeam(team.getId());
        }
        admin.signOut();
    }

    private void setupProperties() {
        config = new Properties();

        // load from user's local file
        loadProperties(USER_CONFIG_FILE, config);

        SYNAPSE_USER = config.getProperty(USER_NAME);
        SYNAPSE_API_KEY = config.getProperty(SYNAPSE_API_KEY_NAME);
        EXPORTER_SYNAPSE_USER_ID = config.getProperty(EXPORTER_SYNAPSE_USER_ID_NAME);
        TEST_USER_ID = Long.parseLong(config.getProperty(TEST_USER_ID_NAME));
    }

    private void loadProperties(final String fileName, final Properties properties) {
        File file = new File(fileName);
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void createSynapseProjectTeam() throws IOException, SynapseException {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);

        // integration test with synapseclient
        // pre-setup - remove current study's project and team info
        Study currentStudy = studiesApi.getUsersStudy().execute().body();
        currentStudy.setSynapseDataAccessTeamId(null);
        currentStudy.setSynapseProjectId(null);

        VersionHolder holder = studiesApi.updateStudy(currentStudy.getIdentifier(), currentStudy).execute().body();
        assertNotNull(holder.getVersion());

        // execute
        studiesApi.createSynapseProjectTeam(TEST_USER_ID.toString()).execute().body();

        // verify study
        Study newStudy = studiesApi.getStudy(currentStudy.getIdentifier()).execute().body();
        assertEquals(newStudy.getIdentifier(), currentStudy.getIdentifier());
        String projectId = newStudy.getSynapseProjectId();
        Long teamId = newStudy.getSynapseDataAccessTeamId();

        // verify if project and team exists
        Entity project = synapseClient.getEntityById(projectId);
        assertNotNull(project);
        assertEquals(project.getEntityType(), "org.sagebionetworks.repo.model.Project");
        this.project = (Project) project;
        Team team = synapseClient.getTeam(teamId.toString());
        assertNotNull(team);
        this.team = team;

        // project acl
        AccessControlList projectAcl = synapseClient.getACL(projectId);
        Set<ResourceAccess> projectRa =  projectAcl.getResourceAccess();
        assertNotNull(projectRa);
        assertEquals(projectRa.size(), 3); // target user, exporter and bridgepf itself
        // first verify exporter
        List<ResourceAccess> retListForExporter = projectRa.stream()
                .filter(ra -> ra.getPrincipalId().equals(Long.parseLong(EXPORTER_SYNAPSE_USER_ID)))
                .collect(Collectors.toList());

        assertNotNull(retListForExporter);
        assertEquals(retListForExporter.size(), 1); // should only have one exporter info
        ResourceAccess exporterRa = retListForExporter.get(0);
        assertNotNull(exporterRa);
        assertEquals(exporterRa.getPrincipalId().toString(), EXPORTER_SYNAPSE_USER_ID);
        assertEquals(exporterRa.getAccessType(), ModelConstants.ENITY_ADMIN_ACCESS_PERMISSIONS);
        // then verify target user
        List<ResourceAccess> retListForUser = projectRa.stream()
                .filter(ra -> ra.getPrincipalId().equals(TEST_USER_ID))
                .collect(Collectors.toList());

        assertNotNull(retListForUser);
        assertEquals(retListForUser.size(), 1); // should only have target user info
        ResourceAccess userRa = retListForUser.get(0);
        assertNotNull(userRa);
        assertEquals(userRa.getPrincipalId(), TEST_USER_ID);
        assertEquals(userRa.getAccessType(), ModelConstants.ENITY_ADMIN_ACCESS_PERMISSIONS);

        // membership invitation to target user
        // (teamId, inviteeId, limit, offset)
        PaginatedResults<MembershipInvtnSubmission> retInvitations =  synapseClient.getOpenMembershipInvitationSubmissions(teamId.toString(), TEST_USER_ID.toString(), 1, 0);
        List<MembershipInvtnSubmission> invitationList = retInvitations.getResults();
        assertEquals(invitationList.size(), 1); // only one invitation submission from newly created team to target user
        MembershipInvtnSubmission invtnSubmission = invitationList.get(0);
        assertEquals(invtnSubmission.getInviteeId(), TEST_USER_ID.toString());
        assertEquals(invtnSubmission.getTeamId(), teamId.toString());
    }

    @Test
    public void crudStudy() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);

        studyId = Tests.randomIdentifier(StudyTest.class);
        Study study = Tests.getStudy(studyId, null);
        assertNull("study version should be null", study.getVersion());
        
        VersionHolder holder = studiesApi.createStudy(study).execute().body();
        assertNotNull(holder.getVersion());

        Study newStudy = studiesApi.getStudy(study.getIdentifier()).execute().body();

        study.addDataGroupsItem("test_user"); // added by the server, required for equality of dataGroups.
        
        // Verify study has password/email templates
        assertNotNull("password policy should not be null", newStudy.getPasswordPolicy());
        assertNotNull("verify email template should not be null", newStudy.getVerifyEmailTemplate());
        assertNotNull("password reset template should not be null", newStudy.getResetPasswordTemplate());
        assertEquals("name should be equal", study.getName(), newStudy.getName());
        assertEquals("minAgeOfConsent should be equal", study.getMinAgeOfConsent(), newStudy.getMinAgeOfConsent());
        assertEquals("sponsorName should be equal", study.getSponsorName(), newStudy.getSponsorName());
        assertEquals("supportEmail should be equal", study.getSupportEmail(), newStudy.getSupportEmail());
        assertEquals("technicalEmail should be equal", study.getTechnicalEmail(), newStudy.getTechnicalEmail());
        assertTrue("usesCustomExportSchedule should be true", study.getUsesCustomExportSchedule());
        assertEquals("consentNotificationEmail should be equal", study.getConsentNotificationEmail(), newStudy.getConsentNotificationEmail());
        assertEquals("userProfileAttributes should be equal", study.getUserProfileAttributes(), newStudy.getUserProfileAttributes());
        assertEquals("taskIdentifiers should be equal", study.getTaskIdentifiers(), newStudy.getTaskIdentifiers());
        assertTrue("dataGroups should be equal", Tests.assertListsEqualIgnoringOrder(study.getDataGroups(), newStudy.getDataGroups()));
        assertEquals("android minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get("Android"),
                newStudy.getMinSupportedAppVersions().get("Android"));
        assertEquals("iOS minSupportedAppVersions should be equal", study.getMinSupportedAppVersions().get("iPhone OS"),
                newStudy.getMinSupportedAppVersions().get("iPhone OS"));
        // This was set to true even though we didn't set it.
        assertTrue("strictUploadValidationEnabled should be true", newStudy.getStrictUploadValidationEnabled());
        // And this is true because admins can set it to true. 
        assertTrue("healthCodeExportEnabled should be true", newStudy.getHealthCodeExportEnabled());
        // And this is also true
        assertTrue("emailVerificationEnabled should be true", newStudy.getEmailVerificationEnabled());
        
        Long oldVersion = newStudy.getVersion();
        alterStudy(newStudy);
        holder = studiesApi.updateStudy(newStudy.getIdentifier(), newStudy).execute().body();
        
        Study newerStudy = studiesApi.getStudy(newStudy.getIdentifier()).execute().body();
        assertTrue(newerStudy.getVersion() > oldVersion);
        
        assertEquals("Altered Test Study [SDK]", newerStudy.getName());
        assertEquals("test3@test.com", newerStudy.getSupportEmail());
        assertEquals("test4@test.com", newerStudy.getConsentNotificationEmail());

        studiesApi.deleteStudy(studyId).execute();
        try {
            studiesApi.getStudy(studyId).execute();
            fail("Should have thrown exception");
        } catch(EntityNotFoundException e) {
            // expected exception
        }
        studyId = null;
    }

    @Test
    public void researcherCannotAccessAnotherStudy() throws Exception {
        TestUser researcher = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.RESEARCHER);
        try {
            studyId = Tests.randomIdentifier(StudyTest.class);
            Study study = Tests.getStudy(studyId, null);

            StudiesApi adminStudiesApi = admin.getClient(StudiesApi.class);
            adminStudiesApi.createStudy(study).execute();

            try {
                StudiesApi resStudiesApi = researcher.getClient(StudiesApi.class);
                resStudiesApi.getStudy(studyId).execute();
                fail("Should not have been able to get this other study");
            } catch(UnauthorizedException e) {
                assertEquals("Unauthorized HTTP response code", 403, e.getStatusCode());
            }
        } finally {
            researcher.signOutAndDeleteUser();
        }
    }

    @Test(expected = UnauthorizedException.class)
    public void butNormalUserCannotAccessStudy() throws Exception {
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, false);
        try {
            StudiesApi studiesApi = user.getClient(StudiesApi.class);
            studiesApi.getUsersStudy().execute();
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void developerCannotSetHealthCodeToExportOrVerifyEmailWorkflow() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        try {
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);
            
            Study study = studiesApi.getUsersStudy().execute().body();
            study.setHealthCodeExportEnabled(true);
            study.setEmailVerificationEnabled(false);
            studiesApi.updateUsersStudy(study).execute();
            
            study = studiesApi.getUsersStudy().execute().body();
            assertFalse("healthCodeExportEnabled should be true", study.getHealthCodeExportEnabled());
            assertTrue("emailVersificationEnabled should be true", study.getEmailVerificationEnabled());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    @Test
    public void adminCanGetAllStudies() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        
        StudyList studies = studiesApi.getStudies(null).execute().body();
        assertTrue("Should be more than zero studies", studies.getTotal() > 0);
    }
    
    @Test
    public void userCannotAccessApisWithDeprecatedClient() throws Exception {
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        Study study = studiesApi.getStudy(Tests.TEST_KEY).execute().body();
        // Set a minimum value that should not any other tests
        if (study.getMinSupportedAppVersions().get("Android") == null) {
            study.getMinSupportedAppVersions().put("Android", 1);
            studiesApi.updateUsersStudy(study).execute();
        }
        TestUser user = TestUserHelper.createAndSignInUser(StudyTest.class, true);
        try {
            
            // This is a version zero client, it should not be accepted
            ClientInfo clientInfo = new ClientInfo();
            clientInfo.setDeviceName("Unknown");
            clientInfo.setOsName("Android");
            clientInfo.setOsVersion("1");
            clientInfo.setAppName(Tests.APP_NAME);
            clientInfo.setAppVersion(0);
            
            ClientManager manager = new ClientManager.Builder()
                    .withSignIn(user.getSignIn())
                    .withClientInfo(clientInfo)
                    .build();
            
            ForConsentedUsersApi usersApi = manager.getClient(ForConsentedUsersApi.class);
            
            usersApi.getScheduledActivities("+00:00", 3, null).execute();
            fail("Should have thrown exception");
            
        } catch(UnsupportedVersionException e) {
            // This is good.
        } finally {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void getStudyUploads() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(StudyTest.class, false, Role.DEVELOPER);
        TestUser user = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        TestUser user2 = TestUserHelper.createAndSignInUser(ParticipantsTest.class, true);
        try {
            UploadsApi devUploadsApi = developer.getClient(UploadsApi.class);
            DateTime startTime = DateTime.now(DateTimeZone.UTC).minusHours(2);
            DateTime endTime = startTime.plusHours(4);
            int count = devUploadsApi.getUploads(startTime, endTime).execute().body().getItems().size();

            // Create a REQUESTED record that we can retrieve through the reporting API.
            UploadRequest request = new UploadRequest();
            request.setName("upload.zip");
            request.setContentType("application/zip");
            request.setContentLength(100L);
            request.setContentMd5("ABC");
            
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            UploadSession uploadSession = usersApi.requestUploadSession(request).execute().body();
            
            UploadSession uploadSession2 = usersApi.requestUploadSession(request).execute().body();
            
            Thread.sleep(1000); // This does depend on a GSI, so pause for a bit.
            
            // This should retrieve both of the user's uploads.
            StudiesApi studiesApi = developer.getClient(StudiesApi.class);
            UploadList results = studiesApi.getUploads(startTime, endTime).execute().body();
            assertEquals(startTime, results.getStartTime());
            assertEquals(endTime, results.getEndTime());

            assertEquals(count+2, results.getItems().size());
            assertNotNull(getUpload(results, uploadSession.getId()));
            assertNotNull(getUpload(results, uploadSession2.getId()));
        } finally {
            if (user != null) {
                user.signOutAndDeleteUser();
            }
            if (user2 != null) {
                user2.signOutAndDeleteUser();
            }
            if (developer != null) {
                developer.signOutAndDeleteUser();
            }
        }
    }

    private Upload getUpload(UploadList results, String guid) {
        for (Upload upload : results.getItems()) {
            if (upload.getUploadId().equals(guid)) {
                return upload;
            }
        }
        return null;
    }
    
    private void alterStudy(Study study) {
        study.setName("Altered Test Study [SDK]");
        study.setSupportEmail("test3@test.com");
        study.setConsentNotificationEmail("test4@test.com");
    }

}
