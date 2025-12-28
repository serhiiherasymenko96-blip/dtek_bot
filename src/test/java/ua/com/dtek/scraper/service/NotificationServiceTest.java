package ua.com.dtek.scraper.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.AddressInfo;
import ua.com.dtek.scraper.dto.ScrapeResult;
import ua.com.dtek.scraper.parser.ScheduleParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class NotificationServiceTest {

    @Mock
    private DatabaseService dbService;

    @Mock
    private DtekScraperService scraperService;

    @Mock
    private ScheduleParser scheduleParser;

    private NotificationService notificationService;
    private Map<String, Address> addresses;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        addresses = new HashMap<>();

        // Create test addresses
        for (int i = 1; i <= 5; i++) {
            String key = "address." + i;
            Address address = new Address("address." + i, "Test Address " + i, "City", "Street " + i, "House " + i);
            addresses.put(key, address);
        }

        notificationService = new NotificationService(dbService, scraperService, scheduleParser, addresses);
    }

    @Test
    public void testRunFullScheduleCheck() throws Exception {
        // Prepare test data
        List<AddressInfo> addressesToCheck = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String key = "address." + i;
            AddressInfo info = new AddressInfo(key, addresses.get(key), null, 0L);
            addressesToCheck.add(info);
        }

        // Mock database service to return our test addresses
        when(dbService.getAddressesForFullCheck(anyLong(), anyLong())).thenReturn(addressesToCheck);

        // Mock scraper service to simulate a slow operation
        doAnswer(invocation -> {
            System.out.println("[DEBUG_LOG] Opening session");
            Thread.sleep(500); // Simulate browser startup time
            return null;
        }).when(scraperService).openSession();

        when(scraperService.checkAddressInSession(anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                System.out.println("[DEBUG_LOG] Checking address: " + invocation.getArgument(1));
                Thread.sleep(1000); // Simulate scraping time
                return new ScrapeResult("Group1", new ArrayList<>());
            });

        // Create a latch to wait for the test to complete
        CountDownLatch testCompletionLatch = new CountDownLatch(1);

        // Run the test in a separate thread
        Thread testThread = new Thread(() -> {
            try {
                // Call the method we're testing
                notificationService.forceCheckAllAddresses(123456789);

                // Process the task queue to execute the queued tasks
                notificationService.processTaskQueue();

                testCompletionLatch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        testThread.start();

        // Wait for the test to complete with a timeout
        boolean completed = testCompletionLatch.await(30, TimeUnit.SECONDS);

        // Verify that the test completed successfully
        assert completed : "Test did not complete within the timeout period";

        // Verify that all addresses were checked
        verify(scraperService, times(5)).openSession();
        verify(scraperService, times(5)).checkAddressInSession(anyString(), anyString(), anyString());
        verify(scraperService, times(5)).closeSession();

        System.out.println("[DEBUG_LOG] Test completed successfully");
    }
}
