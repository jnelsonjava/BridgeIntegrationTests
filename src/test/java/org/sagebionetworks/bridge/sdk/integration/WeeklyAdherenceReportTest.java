package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.*;
import static org.sagebionetworks.bridge.rest.model.ActivityEventUpdateType.MUTABLE;
import static org.sagebionetworks.bridge.rest.model.ParticipantStudyProgress.IN_PROGRESS;
import static org.sagebionetworks.bridge.rest.model.PerformanceOrder.SEQUENTIAL;
import static org.sagebionetworks.bridge.rest.model.Role.DEVELOPER;
import static org.sagebionetworks.bridge.rest.model.Role.STUDY_DESIGNER;
import static org.sagebionetworks.bridge.rest.model.TestFilter.PRODUCTION;
import static org.sagebionetworks.bridge.sdk.integration.InitListener.CLINIC_VISIT;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.api.SchedulesV2Api;
import org.sagebionetworks.bridge.rest.api.StudiesApi;
import org.sagebionetworks.bridge.rest.api.StudyAdherenceApi;
import org.sagebionetworks.bridge.rest.api.StudyParticipantsApi;
import org.sagebionetworks.bridge.rest.exceptions.BadRequestException;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.AdherenceRecord;
import org.sagebionetworks.bridge.rest.model.AdherenceRecordUpdates;
import org.sagebionetworks.bridge.rest.model.AdherenceReportSearch;
import org.sagebionetworks.bridge.rest.model.AdherenceStatistics;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentReference2;
import org.sagebionetworks.bridge.rest.model.CustomEvent;
import org.sagebionetworks.bridge.rest.model.Enrollment;
import org.sagebionetworks.bridge.rest.model.EventStreamWindow;
import org.sagebionetworks.bridge.rest.model.Schedule2;
import org.sagebionetworks.bridge.rest.model.Session;
import org.sagebionetworks.bridge.rest.model.SessionCompletionState;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyActivityEvent;
import org.sagebionetworks.bridge.rest.model.StudyActivityEventList;
import org.sagebionetworks.bridge.rest.model.TestFilter;
import org.sagebionetworks.bridge.rest.model.TimeWindow;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.rest.model.WeeklyAdherenceReport;
import org.sagebionetworks.bridge.rest.model.WeeklyAdherenceReportList;
import org.sagebionetworks.bridge.rest.model.WeeklyAdherenceReportRow;
import org.sagebionetworks.bridge.user.TestUser;
import org.sagebionetworks.bridge.user.TestUserHelper;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class WeeklyAdherenceReportTest {

    private TestUser participant1;
    private TestUser participant2;
    private TestUser developer;
    
    private Schedule2 schedule;
    private Assessment assessmentA;
    private Assessment assessmentB;
    private String asmtATag;
    private String asmtBTag;
    
    private String studyId;
    private Study study;

    @Before
    public void before() throws Exception {
        developer = TestUserHelper.createAndSignInUser(getClass(), false, DEVELOPER, STUDY_DESIGNER);
        ForDevelopersApi developersApi = developer.getClient(ForDevelopersApi.class);
        AssessmentsApi asmtsApi = developer.getClient(AssessmentsApi.class);

        List<CustomEvent> events = new ArrayList<>();
        events.add(new CustomEvent().eventId(InitListener.FAKE_ENROLLMENT).updateType(MUTABLE));
        events.add(new CustomEvent().eventId(InitListener.CLINIC_VISIT).updateType(MUTABLE));

        studyId = RandomStringUtils.randomAlphabetic(7);
        study = new Study();
        study.setIdentifier(studyId);
        study.setName(studyId);
        study.setCustomEvents(events);
        VersionHolder version = developer.getClient(StudiesApi.class).createStudy(study).execute().body();
        study.setVersion(version.getVersion());;
        
        // If there's a schedule associated to study 1, we need to delete it.
        TestUser admin = TestUserHelper.getSignedInAdmin();
        if (study.getScheduleGuid() != null) {
            admin.getClient(SchedulesV2Api.class).deleteSchedule(study.getScheduleGuid()).execute();
        }        
        
        asmtATag = Tests.randomIdentifier(getClass());
        asmtBTag = Tests.randomIdentifier(getClass());
        
        assessmentA = new Assessment()
                .identifier(asmtATag)
                .osName("Universal")
                .ownerId(developer.getSession().getOrgMembership())
                .title("Assessment A");
        assessmentA = asmtsApi.createAssessment(assessmentA).execute().body();
        
        assessmentB = new Assessment()
                .identifier(asmtBTag)
                .osName("Universal")
                .ownerId(developer.getSession().getOrgMembership())
                .title("Assessment B");
        assessmentB = asmtsApi.createAssessment(assessmentB).execute().body();
        
        Session s1 = new Session()
                .name("Session #1")
                .addStartEventIdsItem("enrollment")
                .delay("P2D")
                .interval("P3D")
                .performanceOrder(SEQUENTIAL)
                .addAssessmentsItem(asmtToReference(assessmentA))
                .addTimeWindowsItem(new TimeWindow()
                        .startTime("08:00").expiration("PT6H"))
                .addTimeWindowsItem(new TimeWindow()
                        .startTime("16:00").expiration("PT6H").persistent(true));

        Session s2 = new Session()
                .name("Session #2")
                .addStartEventIdsItem("enrollment")
                .interval("P7D")
                .performanceOrder(SEQUENTIAL)
                .addAssessmentsItem(asmtToReference(assessmentA))
                .addAssessmentsItem(asmtToReference(assessmentB))
                .addTimeWindowsItem(new TimeWindow()
                        .startTime("08:00").expiration("PT12H"));
        
        Session s3 = new Session()
                .name("Session #3")
                .addStartEventIdsItem(CLINIC_VISIT)
                .performanceOrder(SEQUENTIAL)
                .addAssessmentsItem(asmtToReference(assessmentB))
                .addTimeWindowsItem(new TimeWindow().startTime("08:00").persistent(true));
        
        schedule = new Schedule2()
                .name("AdheredRecordsTest Schedule")
                .duration("P22D")
                .addSessionsItem(s1)
                .addSessionsItem(s2)
                .addSessionsItem(s3);
        schedule = developersApi.saveScheduleForStudy(studyId, schedule).execute().body();
    }
    
    @After
    public void after() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        AssessmentsApi assessmentsApi = admin.getClient(AssessmentsApi.class);
        SchedulesV2Api schedulesApi = admin.getClient(SchedulesV2Api.class);
        if (participant1 != null) {
            participant1.signOutAndDeleteUser();
        }
        if (participant2 != null) {
            participant2.signOutAndDeleteUser();
        }
        if (schedule != null && schedule.getGuid() != null) {
            schedulesApi.deleteSchedule(schedule.getGuid()).execute();
        }
        if (assessmentA != null && assessmentA.getGuid() != null) {
            assessmentsApi.deleteAssessment(assessmentA.getGuid(), true).execute();
        }
        if (assessmentB != null && assessmentB.getGuid() != null) {
            assessmentsApi.deleteAssessment(assessmentB.getGuid(), true).execute();
        }
        if (developer != null) {
            developer.signOutAndDeleteUser();
        }
        if (study != null) {
            admin.getClient(ForAdminsApi.class).deleteStudy(study.getIdentifier(), true).execute();
        }
    }

    @Test
    public void test() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        
        participant1 = TestUserHelper.createAndSignInUser(getClass(), true);
        admin.getClient(ForAdminsApi.class).enrollParticipant(
                studyId, new Enrollment().userId(participant1.getUserId())).execute();
        participant1.signInAgain();
        
        StudyAdherenceApi adherenceApi = developer.getClient(StudyAdherenceApi.class);

        AdherenceReportSearch search = new AdherenceReportSearch();
        int startingTotal = adherenceApi.getWeeklyAdherenceReports(
                studyId, search).execute().body().getTotal();
        
        ForConsentedUsersApi userApi = participant1.getClient(ForConsentedUsersApi.class);
        StudyParticipantsApi devApi = developer.getClient(StudyParticipantsApi.class);
        
        // let's do the first available thing in the report
        StudyActivityEventList events = userApi.getStudyActivityEvents(studyId).execute().body();
        StudyActivityEvent enrollmentEvent = events.getItems().stream()
                .filter(event -> event.getEventId().equals("enrollment"))
                .findFirst().get();
        
        WeeklyAdherenceReport report = devApi.getWeeklyAdherenceReport(studyId, participant1.getUserId()).execute().body();
        WeeklyAdherenceReportRow row1 = report.getRows().get(0);
        assertEquals("Session #1 / Week 1", row1.getLabel());
        assertEquals(":Session #1:Week 1:", row1.getSearchableLabel());
        assertEquals("Session #1", row1.getSessionName());
        assertEquals(Integer.valueOf(1), row1.getWeekInStudy());
        
        WeeklyAdherenceReportRow row2 = report.getRows().get(1);
        assertEquals("Session #2 / Week 1", row2.getLabel());
        assertEquals(":Session #2:Week 1:", row2.getSearchableLabel());
        assertEquals("Session #2", row2.getSessionName());
        assertEquals(Integer.valueOf(1), row2.getWeekInStudy());
        
        assertEquals(participant1.getUserId(), report.getParticipant().getIdentifier());
        assertEquals(participant1.getEmail(), report.getParticipant().getEmail());
        assertEquals(Integer.valueOf(0), report.getWeeklyAdherencePercent());
        assertEquals(DateTime.now().toLocalDate(), report.getStartDate());
        
        // there is no Session #3, it does not apply to this user
        assertEquals(2, report.getRows().size());
        
        EventStreamWindow win = report.getByDayEntries().get("0").get(1).getTimeWindows().get(0);
        assertEquals(SessionCompletionState.UNSTARTED, win.getState());
        String instanceGuid = win.getSessionInstanceGuid();
        
        AdherenceRecord rec = new AdherenceRecord();
        rec.setInstanceGuid(instanceGuid);
        rec.setEventTimestamp(enrollmentEvent.getTimestamp());
        rec.setStartedOn(DateTime.now());
        rec.setFinishedOn(DateTime.now());
        
        AdherenceRecordUpdates updates = new AdherenceRecordUpdates();
        updates.setRecords(ImmutableList.of(rec));
        userApi.updateAdherenceRecords(studyId, updates).execute();
        
        report = devApi.getWeeklyAdherenceReport(studyId, participant1.getUserId()).execute().body();
        assertEquals(Integer.valueOf(100), report.getWeeklyAdherencePercent());

        win = report.getByDayEntries().get("0").get(1).getTimeWindows().get(0);
        assertEquals(SessionCompletionState.COMPLETED, win.getState());
        
        // Paginated APIs
        participant2 = TestUserHelper.createAndSignInUser(getClass(), true);
        admin.getClient(ForAdminsApi.class).enrollParticipant(
                studyId, new Enrollment().userId(participant2.getUserId())).execute();
        
        // does not exist unless you force it by requesting it
        devApi.getWeeklyAdherenceReport(studyId, participant2.getUserId()).execute().body();

        search = new AdherenceReportSearch();
        WeeklyAdherenceReportList allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute()
                .body();
        assertEquals(Integer.valueOf(50), allReports.getRequestParams().getPageSize());
        assertEquals(TestFilter.TEST, allReports.getRequestParams().getTestFilter());
        assertEquals(Integer.valueOf(startingTotal+2), allReports.getTotal()); // only one person
        
        Set<String> emails = allReports.getItems().stream()
                .map(r -> r.getParticipant().getEmail()).collect(Collectors.toSet());
        Set<String> ids = allReports.getItems().stream()
                .map(r -> r.getParticipant().getIdentifier()).collect(Collectors.toSet());
        assertTrue(emails.containsAll(ImmutableSet.of(participant1.getEmail(), participant2.getEmail())));
        assertTrue(ids.containsAll(ImmutableSet.of(participant1.getUserId(), participant2.getUserId())));
        
        report = allReports.getItems().stream()
                .filter(r -> r.getParticipant().getIdentifier().equals(participant1.getUserId()))
                .findFirst()
                .orElse(null);
        assertEquals(Integer.valueOf(100), report.getWeeklyAdherencePercent());

        report = allReports.getItems().stream()
                .filter(r -> r.getParticipant().getIdentifier().equals(participant2.getUserId()))
                .findFirst()
                .orElse(null);
        assertEquals(Integer.valueOf(0), report.getWeeklyAdherencePercent());

        // verify there are filters
        search = new AdherenceReportSearch().addLabelFiltersItem("Belgium");
        allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute().body();
        
        assertEquals(Integer.valueOf(0), allReports.getTotal());
        assertEquals(ImmutableList.of("Belgium"), allReports.getRequestParams().getLabelFilters());

        // Only user #2 is under the 30% adherence bar
        search = new AdherenceReportSearch().adherenceMax(30);
        allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute().body();
        assertEquals(Integer.valueOf(1), allReports.getTotal());
        report = allReports.getItems().get(0);
        assertEquals(participant2.getEmail(), report.getParticipant().getEmail());
        assertNull(allReports.getRequestParams().getAdherenceMin());
        assertEquals(Integer.valueOf(30), allReports.getRequestParams().getAdherenceMax());
        
        search = new AdherenceReportSearch().adherenceMin(50).adherenceMax(100);
        allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute().body();
        assertEquals(Integer.valueOf(1), allReports.getTotal());
        assertEquals(Integer.valueOf(50), allReports.getRequestParams().getAdherenceMin());
        assertEquals(Integer.valueOf(100), allReports.getRequestParams().getAdherenceMax());
        
        // test filter (it comes back test because the caller is a developer).
        search = new AdherenceReportSearch().testFilter(PRODUCTION);
        allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute().body();
        assertEquals(Integer.valueOf(startingTotal+2), allReports.getTotal());
        assertEquals(TestFilter.TEST, allReports.getRequestParams().getTestFilter());

        // ID filter
        search = new AdherenceReportSearch().idFilter(participant2.getEmail());
        allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute().body();
        assertEquals(participant2.getUserId(), allReports.getItems().get(0).getParticipant().getIdentifier());
        assertEquals(participant2.getEmail(), allReports.getRequestParams().getIdFilter());
        
        // Progressions state filter
        search = new AdherenceReportSearch().addProgressionFiltersItem(IN_PROGRESS);
        allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute().body();
        assertTrue(allReports.getTotal() >= 2); // our participants
        for (WeeklyAdherenceReport oneReport : allReports.getItems()) {
            assertTrue(oneReport.getProgression() == IN_PROGRESS);
        }
        assertEquals(ImmutableList.of(IN_PROGRESS), allReports.getRequestParams().getProgressionFilters());
        
        search = new AdherenceReportSearch().offsetBy(1).pageSize(5);
        allReports = adherenceApi.getWeeklyAdherenceReports(studyId, search).execute().body();
        assertEquals(Integer.valueOf(1), allReports.getRequestParams().getOffsetBy());
        assertEquals(Integer.valueOf(5), allReports.getRequestParams().getPageSize());
        
        AdherenceStatistics stats = adherenceApi.getAdherenceStatistics(studyId, 30).execute().body();
        assertEquals(Integer.valueOf(1), stats.getCompliant());
        assertEquals(Integer.valueOf(1), stats.getNoncompliant());
        assertEquals(Integer.valueOf(2), stats.getTotalActive());
        
        assertEquals("Session #1 / Week 1", stats.getEntries().get(0).getLabel());
        assertEquals(Integer.valueOf(2), stats.getEntries().get(0).getTotalActive());
        assertEquals("Session #2 / Week 1", stats.getEntries().get(1).getLabel());
        assertEquals(Integer.valueOf(2), stats.getEntries().get(1).getTotalActive());
        
        stats = adherenceApi.getAdherenceStatistics(studyId, 70).execute().body();
        assertEquals(Integer.valueOf(1), stats.getCompliant());
        assertEquals(Integer.valueOf(1), stats.getNoncompliant());
        assertEquals(Integer.valueOf(2), stats.getTotalActive());
        
        try {
            adherenceApi.getAdherenceStatistics(studyId, null).execute().body();    
        } catch(BadRequestException e) {
            assertEquals("An adherence threshold value must be supplied in the request or set as a study default.", e.getMessage());
        }
        try {
            adherenceApi.getAdherenceStatistics(studyId, 0).execute().body();    
        } catch(BadRequestException e) {
            assertEquals("Adherence threshold must be from 1-100.", e.getMessage());
        }
        try {
            adherenceApi.getAdherenceStatistics(studyId, 101).execute().body();    
        } catch(BadRequestException e) {
            assertEquals("Adherence threshold must be from 1-100.", e.getMessage());
        }
        try {
            adherenceApi.getAdherenceStatistics("nonexistent-study", null).execute();    
        } catch(EntityNotFoundException e) {
            assertEquals("Study not found.", e.getMessage());
        }
        try {
            adherenceApi.getAdherenceStatistics(Tests.STUDY_ID_2, null).execute();    
        } catch(EntityNotFoundException e) {
            assertEquals("Schedule not found.", e.getMessage());
        }
    }
    
    private AssessmentReference2 asmtToReference(Assessment asmt) {
        return new AssessmentReference2()
                .appId(TEST_APP_ID)
                .identifier(asmt.getIdentifier())
                .guid(asmt.getGuid());
    }
}
