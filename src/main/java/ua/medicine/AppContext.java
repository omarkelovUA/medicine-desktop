package ua.medicine;

import ua.medicine.persistence.Database;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.persistence.PersonRepository;
import ua.medicine.service.DoseService;
import ua.medicine.service.HistoryService;
import ua.medicine.service.MedicineService;
import ua.medicine.service.PersonService;
import ua.medicine.service.ReminderService;
import ua.medicine.sync.CalendarSyncService;
import ua.medicine.sync.GoogleAuthService;
import ua.medicine.sync.GoogleCalendarGatewayFactory;
import ua.medicine.sync.GoogleLinkService;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wires the whole application by hand. */
public final class AppContext implements AutoCloseable {

    private final AppPaths paths;
    private final Clock clock = Clock.systemDefaultZone();
    private final Database database;
    private final PersonRepository personRepository;
    private final MedicineRepository medicineRepository;
    private final DoseRepository doseRepository;
    private final GoogleAuthService googleAuth;
    private final CalendarSyncService sync;
    private final PersonService personService;
    private final MedicineService medicineService;
    private final DoseService doseService;
    private final HistoryService historyService;
    private final ReminderService reminderService;
    private final GoogleLinkService googleLink;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "io");
        t.setDaemon(true);
        return t;
    });

    public AppContext(AppPaths paths) {
        this.paths = paths;
        database = new Database("jdbc:sqlite:" + paths.db());
        database.migrate();
        personRepository = new PersonRepository(database);
        medicineRepository = new MedicineRepository(database);
        doseRepository = new DoseRepository(database);
        googleAuth = new GoogleAuthService(paths);
        var gatewayFactory = new GoogleCalendarGatewayFactory(googleAuth);
        sync = new CalendarSyncService(personRepository, medicineRepository, doseRepository, gatewayFactory, clock);
        personService = new PersonService(personRepository, medicineRepository);
        medicineService = new MedicineService(medicineRepository, doseRepository, clock, sync);
        doseService = new DoseService(doseRepository, medicineRepository, clock, sync);
        historyService = new HistoryService(doseRepository, medicineRepository, personRepository, clock);
        reminderService = new ReminderService(doseRepository, medicineRepository, personRepository, clock);
        googleLink = new GoogleLinkService(googleAuth, gatewayFactory, personRepository, doseRepository, sync);
    }

    public AppPaths paths() {
        return paths;
    }

    public Clock clock() {
        return clock;
    }

    public PersonRepository personRepository() {
        return personRepository;
    }

    public MedicineRepository medicineRepository() {
        return medicineRepository;
    }

    public DoseRepository doseRepository() {
        return doseRepository;
    }

    public GoogleAuthService googleAuth() {
        return googleAuth;
    }

    public CalendarSyncService sync() {
        return sync;
    }

    public PersonService personService() {
        return personService;
    }

    public MedicineService medicineService() {
        return medicineService;
    }

    public DoseService doseService() {
        return doseService;
    }

    public HistoryService historyService() {
        return historyService;
    }

    public ReminderService reminderService() {
        return reminderService;
    }

    public GoogleLinkService googleLink() {
        return googleLink;
    }

    /** Background thread for slow work (Google sign-in). */
    public ExecutorService io() {
        return io;
    }

    @Override
    public void close() {
        sync.shutdown();
        io.shutdownNow();
        database.close();
    }
}
