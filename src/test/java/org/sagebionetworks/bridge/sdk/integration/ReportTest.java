package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.joda.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.sdk.integration.TestUserHelper.TestUser;

import org.sagebionetworks.bridge.sdk.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.sdk.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.sdk.rest.api.ReportsApi;
import org.sagebionetworks.bridge.sdk.rest.api.StudiesApi;
import org.sagebionetworks.bridge.sdk.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.sdk.rest.model.DateRangeResourceListReportData;
import org.sagebionetworks.bridge.sdk.rest.model.ReportData;
import org.sagebionetworks.bridge.sdk.rest.model.ReportIndex;
import org.sagebionetworks.bridge.sdk.rest.model.ReportType;
import org.sagebionetworks.bridge.sdk.rest.model.ReportTypeResourceListReportIndex;
import org.sagebionetworks.bridge.sdk.rest.model.Role;
import org.sagebionetworks.bridge.sdk.rest.model.Study;
import org.sagebionetworks.bridge.sdk.rest.model.VersionHolder;

import com.google.gson.JsonObject;

public class ReportTest {
    
    private static final LocalDate SEARCH_END_DATE = LocalDate.parse("2016-02-20");

    private static final LocalDate SEARCH_START_DATE = LocalDate.parse("2016-02-01");
    
    private static LocalDate TIME1 = LocalDate.parse("2016-02-02");
    private static LocalDate TIME2 = LocalDate.parse("2016-02-03");
    private static LocalDate TIME3 = LocalDate.parse("2016-02-04");
    
    private static JsonObject DATA1 = new JsonObject();
    private static JsonObject DATA2 = new JsonObject();
    private static JsonObject DATA3 = new JsonObject();
    
    private static ReportData REPORT1;
    private static ReportData REPORT2;
    private static ReportData REPORT3;
    
    private TestUser admin;
    
    private TestUser user;
    
    private String reportId;
    
    @Before
    public void before() throws Exception {
        REPORT1 = new ReportData();
        REPORT1.setDate(TIME1);
        REPORT1.setData(DATA1);
        REPORT2 = new ReportData();
        REPORT2.setDate(TIME2);
        REPORT2.setData(DATA2);
        REPORT3 = new ReportData();
        REPORT3.setDate(TIME3);
        REPORT3.setData(DATA3);
        
        this.admin = TestUserHelper.getSignedInAdmin();
        this.user = TestUserHelper.createAndSignInUser(ReportTest.class, true);
        this.reportId = Tests.randomIdentifier(ReportTest.class);
    }

    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void developerCanCrudParticipantReport() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Role.DEVELOPER);
        String userId = user.getSession().getId();
        try {
            ReportsApi reportsApi = developer.getClient(ReportsApi.class);
            
            reportsApi.addParticipantReportRecord(userId, reportId, REPORT1).execute();
            reportsApi.addParticipantReportRecord(userId, reportId, REPORT2).execute();
            reportsApi.addParticipantReportRecord(userId, reportId, REPORT3).execute();
            
            ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
            
            DateRangeResourceListReportData results = usersApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
            assertEquals((Integer)3, results.getTotal());
            assertEquals(SEARCH_START_DATE, results.getStartDate());
            assertEquals(SEARCH_END_DATE, results.getEndDate());
            
            // This search is out of range, and should return no results.
            results = usersApi.getParticipantReportRecords(reportId, SEARCH_START_DATE.plusDays(30),
                    SEARCH_END_DATE.plusDays(30)).execute().body();
            assertEquals((Integer)0, results.getTotal());
            assertEquals(0, results.getItems().size());
            
            // We should see indices for this participant report
            ReportTypeResourceListReportIndex indices = reportsApi.getReportIndices("participant").execute().body();
            assertTrue(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.PARTICIPANT, indices.getReportType());
            
            // but not if we ask for study reports
            indices = reportsApi.getReportIndices("study").execute().body();
            assertFalse(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.STUDY, indices.getReportType());
            
            // delete
            reportsApi.deleteAllParticipantReportRecords(userId, reportId).execute();
            results = usersApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
            assertFalse(containsThisIdentifier(indices, reportId));
        } finally {
            developer.signOutAndDeleteUser();
            
            ReportsApi reportsApi = admin.getClient(ReportsApi.class);
            reportsApi.deleteParticipantReportIndex(reportId).execute();
        }
    }

    @Test
    public void workerCanCrudParticipantReport() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        StudiesApi studiesApi = admin.getClient(StudiesApi.class);
        Study study = studiesApi.getStudy("api").execute().body();
        study.setHealthCodeExportEnabled(true);
        VersionHolder versionHolder = studiesApi.updateStudy(study.getIdentifier(), study).execute().body();
        study.setVersion(versionHolder.getVersion());
        
        // Make this worker a researcher solely for the purpose of getting the healthCode needed to user the worker
        // API
        TestUser worker = TestUserHelper.createAndSignInUser(ReportTest.class, false, Role.WORKER, Role.RESEARCHER);
        
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, false, Role.DEVELOPER);
        
        String healthCode = worker.getClient(ParticipantsApi.class)
                .getParticipant(user.getSession().getId()).execute()
                .body().getHealthCode();
        assertNotNull(healthCode);
        
        String userId = user.getSession().getId();
        try {
            REPORT1.setHealthCode(healthCode);
            REPORT2.setHealthCode(healthCode);
            REPORT3.setHealthCode(healthCode);
            
            ReportsApi workerReportsApi = worker.getClient(ReportsApi.class);
            workerReportsApi.addParticipantReportRecordForWorker(reportId, REPORT1).execute();
            workerReportsApi.addParticipantReportRecordForWorker(reportId, REPORT2).execute();
            workerReportsApi.addParticipantReportRecordForWorker(reportId, REPORT3).execute();
            
            ReportsApi userReportsApi = user.getClient(ReportsApi.class);
            DateRangeResourceListReportData results = userReportsApi.getParticipantReportRecords(reportId,
                    SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
            
            assertEquals((Integer)3, results.getTotal());
            assertEquals(SEARCH_START_DATE, results.getStartDate());
            assertEquals(SEARCH_END_DATE, results.getEndDate());
            
            // This search is out of range, and should return no results.
            results = userReportsApi.getParticipantReportRecords(reportId, SEARCH_START_DATE.plusDays(30), 
                    SEARCH_END_DATE.plusDays(30)).execute().body();
            assertEquals((Integer)0, results.getTotal());
            assertEquals(0, results.getItems().size());
            
            // delete. Must be done by developer
            ReportsApi developerReportsApi = developer.getClient(ReportsApi.class);
            developerReportsApi.deleteAllParticipantReportRecords(userId, reportId).execute();
            
            results = userReportsApi.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE)
                    .execute().body();
            assertEquals((Integer)0, results.getTotal());
            assertEquals(0, results.getItems().size());
        } finally {
            study.setHealthCodeExportEnabled(false);
            studiesApi.updateStudy(study.getIdentifier(), study).execute();

            worker.signOutAndDeleteUser();
            developer.signOutAndDeleteUser();
            
            admin.getClient(ReportsApi.class).deleteParticipantReportIndex(reportId).execute();
        }
    }

    @Test
    public void canCrudStudyReport() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Role.DEVELOPER);
        try {
            ReportsApi devReportClient = developer.getClient(ReportsApi.class);
            devReportClient.addStudyReportRecord(reportId, REPORT1).execute();
            devReportClient.addStudyReportRecord(reportId, REPORT2).execute();
            devReportClient.addStudyReportRecord(reportId, REPORT3).execute();
            
            DateRangeResourceListReportData results = devReportClient.getStudyReportRecords(reportId, SEARCH_START_DATE,
                    SEARCH_END_DATE).execute().body();
            assertEquals((Integer)3, results.getTotal());
            assertEquals(SEARCH_START_DATE, results.getStartDate());
            assertEquals(SEARCH_END_DATE, results.getEndDate());

            // This search is out of range, and should return no results.
            results = devReportClient
                    .getParticipantReportRecords(reportId, SEARCH_START_DATE.minusDays(30), SEARCH_END_DATE.minusDays(30)).execute().body();
            assertEquals((Integer)0, results.getTotal());
            assertEquals(0, results.getItems().size());
            
            // We should see indices for this study report
            ReportTypeResourceListReportIndex indices = devReportClient.getReportIndices("study").execute().body();
            assertTrue(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.STUDY, indices.getReportType());
            
            // but not if we use the other type
            indices = devReportClient.getReportIndices("participant").execute().body();
            assertFalse(containsThisIdentifier(indices, reportId));
            assertEquals(ReportType.PARTICIPANT, indices.getReportType());
            
            developer.getClient(ReportsApi.class).deleteAllStudyReportRecords(reportId).execute();
            results = devReportClient.getParticipantReportRecords(reportId, SEARCH_START_DATE, SEARCH_END_DATE).execute().body();
            assertEquals((Integer)0, results.getTotal());
            assertEquals(0, results.getItems().size());
        } finally {
            developer.signOutAndDeleteUser();
        }
    }
    
    @Test
    public void correctExceptionsOnBadRequest() throws Exception {
        TestUser developer = TestUserHelper.createAndSignInUser(ReportTest.class, true, Role.DEVELOPER);
        try {
            ReportsApi devReportClient = developer.getClient(ReportsApi.class);
            try {
                devReportClient.getStudyReportRecords(reportId, LocalDate.parse("2010-10-10"), LocalDate.parse("2012-10-10")).execute();
                fail("Should have thrown an exception");
            } catch(BadRequestException e) {
                assertEquals("Date range cannot exceed 45 days, startDate=2010-10-10, endDate=2012-10-10", e.getMessage());
            }
            try {
                devReportClient.getStudyReportRecords(reportId, SEARCH_END_DATE, SEARCH_START_DATE).execute();
            } catch(BadRequestException e) {
                assertEquals("Start date 2016-02-20 can't be after end date 2016-02-01", e.getMessage());
            }
            try {
                devReportClient.getParticipantReportRecords(reportId, LocalDate.parse("2010-10-10"), LocalDate.parse("2012-10-10")).execute();
                fail("Should have thrown an exception");
            } catch(BadRequestException e) {
                assertEquals("Date range cannot exceed 45 days, startDate=2010-10-10, endDate=2012-10-10", e.getMessage());
            }
            try {
                devReportClient.getParticipantReportRecords(reportId, SEARCH_END_DATE, SEARCH_START_DATE).execute();
            } catch(BadRequestException e) {
                assertEquals("Start date 2016-02-20 can't be after end date 2016-02-01", e.getMessage());
            }
        } finally {
            developer.signOutAndDeleteUser();
        }
    }

    private boolean containsThisIdentifier(ReportTypeResourceListReportIndex indices, String identifier) {
        for (ReportIndex index : indices.getItems()) {
            if (index.getIdentifier().equals(identifier)) {
                return true;
            }
        }
        return false;
    }
}
