import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * MedMate - Offline Medicine Schedule & Interaction Checker.
 * Pure Java Swing + AWT. No backend, no internet. Data is stored in ~/.medmate/data.ser
 *
 * Compile:  javac MedMate.java
 * Run:      java MedMate        (Java 17+)
 */
public class MedMate extends JFrame {

    // =====================================================================
    //  MODEL
    // =====================================================================
    enum Status { TAKEN, MISSED, SKIPPED }

    enum Slot {
        MORNING("Morning", 0xF2A93B), AFTERNOON("Afternoon", 0x3B9AE1),
        EVENING("Evening", 0xE0627A), NIGHT("Night", 0x5B5BD6);
        final String title; final Color color;
        Slot(String t, int rgb) { title = t; color = new Color(rgb); }
        static Slot of(LocalTime t) {
            int h = t.getHour();
            if (h >= 5 && h < 12) return MORNING;
            if (h >= 12 && h < 17) return AFTERNOON;
            if (h >= 17 && h < 21) return EVENING;
            return NIGHT;
        }
    }

    static class Medicine implements Serializable {
        private static final long serialVersionUID = 1L;
        String id = UUID.randomUUID().toString();
        String name = "", dosage = "", notes = "";
        List<LocalTime> times = new ArrayList<>();
        boolean[] days = new boolean[7];          // 0 = Monday
        int stock = 30;
        LocalDateTime created = LocalDateTime.now();
        boolean scheduledOn(DayOfWeek d) { return days[d.getValue() - 1]; }
    }

    static class LogEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        LocalDate date; String medId, medName; LocalTime time; Status status;
        LogEntry(LocalDate d, Medicine m, LocalTime t, Status s) {
            date = d; medId = m.id; medName = m.name; time = t; status = s;
        }
    }

    static class Data implements Serializable {
        private static final long serialVersionUID = 1L;
        List<Medicine> meds = new ArrayList<>();
        List<LogEntry> logs = new ArrayList<>();
        boolean elder, colorBlind;
    }

    /** One scheduled dose on one date (not persisted; built from medicines + logs). */
    static class Dose {
        final Medicine med; final LocalDate date; final LocalTime time; LogEntry log;
        Dose(Medicine m, LocalDate d, LocalTime t) { med = m; date = d; time = t; }
        String key() { return med.id + "|" + date + "|" + time; }
        LocalDateTime due() { return LocalDateTime.of(date, time); }
    }


    static class Store {
        static final File FILE = new File(System.getProperty("user.home"), ".medmate/data.ser");
        static Data load() {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(FILE))) {
                return (Data) in.readObject();
            } catch (Exception e) { return new Data(); }
        }
        static void save(Data d) {
            try {
                FILE.getParentFile().mkdirs();
                try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(FILE))) {
                    out.writeObject(d);
                }
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // =====================================================================
    //  INTERACTION CHECKER (built-in offline list)
    //  Educational only - NOT a substitute for a doctor or pharmacist.
    // =====================================================================
    static class Interactions {
        record Rule(String a, String b, String msg) {}
        static final List<Rule> RULES = List.of(
            new Rule("aspirin", "ibuprofen", "Higher risk of stomach bleeding; ibuprofen can also weaken aspirin's heart protection."),
            new Rule("aspirin", "warfarin", "Greatly increased bleeding risk."),
            new Rule("aspirin", "diclofenac", "Higher risk of stomach ulcers and bleeding."),
            new Rule("aspirin", "clopidogrel", "Higher bleeding risk; only combine if a doctor prescribed both."),
            new Rule("ibuprofen", "warfarin", "Higher bleeding risk."),
            new Rule("ibuprofen", "lisinopril", "May reduce the blood-pressure effect and strain the kidneys."),
            new Rule("ibuprofen", "naproxen", "Two NSAIDs together raise the risk of stomach and kidney problems."),
            new Rule("paracetamol", "warfarin", "Regular high doses of paracetamol can raise bleeding risk."),
            new Rule("lisinopril", "potassium", "Can cause dangerously high potassium levels."),
            new Rule("sildenafil", "nitroglycerin", "Severe, dangerous drop in blood pressure."),
            new Rule("simvastatin", "clarithromycin", "Higher risk of serious muscle damage."),
            new Rule("fluoxetine", "tramadol", "Risk of serotonin syndrome and seizures."),
            new Rule("sertraline", "tramadol", "Risk of serotonin syndrome and seizures."),
            new Rule("omeprazole", "clopidogrel", "Omeprazole can make clopidogrel less effective."),
            new Rule("levothyroxine", "calcium", "Calcium blocks absorption; take at least 4 hours apart."),
            new Rule("ciprofloxacin", "antacid", "Antacids reduce antibiotic absorption; separate by 2 hours."),
            new Rule("metformin", "alcohol", "Risk of lactic acidosis."),
            new Rule("digoxin", "amiodarone", "Digoxin levels can rise to toxic levels.")
        );

        static String pair(String x, String y) {
            String a = x.toLowerCase(), b = y.toLowerCase();
            if (a.isBlank() || b.isBlank()) return null;
            for (Rule r : RULES)
                if ((a.contains(r.a) && b.contains(r.b)) || (a.contains(r.b) && b.contains(r.a))) return r.msg;
            return null;
        }
        /** Warnings between a candidate name and a list of existing medicines. */
        static List<String> check(String name, List<Medicine> others) {
            List<String> out = new ArrayList<>();
            for (Medicine o : others) {
                String m = pair(name, o.name);
                if (m != null) out.add(name.trim() + " + " + o.name + ": " + m);
            }
            return out;
        }
        static List<String> checkAll(List<Medicine> meds) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < meds.size(); i++)
                for (int j = i + 1; j < meds.size(); j++) {
                    String m = pair(meds.get(i).name, meds.get(j).name);
                    if (m != null) out.add(meds.get(i).name + " + " + meds.get(j).name + ": " + m);
                }
            return out;
        }
    }

    // =====================================================================
    //  THEME  (normal / large-text high-contrast / colour-blind safe)
    // =====================================================================
    static class Theme {
        static boolean elder, cb;
        static Color bg()        { return elder ? Color.WHITE : new Color(0xF3F5F9); }
        static Color card()      { return Color.WHITE; }
        static Color text()      { return elder ? Color.BLACK : new Color(0x1F2937); }
        static Color sub()       { return elder ? new Color(0x222222) : new Color(0x6B7280); }
        static Color accent()    { return elder ? new Color(0x0B2A6F) : new Color(0x2F5D8A); }
        static Color highlight() { return elder ? new Color(0xFFD400) : new Color(0x1B9AAA); }
        static Color ok()        { return cb ? new Color(0x0072B2) : elder ? new Color(0x006400) : new Color(0x2E9E5B); }
        static Color bad()       { return cb ? new Color(0xD55E00) : elder ? new Color(0xB00020) : new Color(0xD64545); }
        static Color warn()      { return cb ? new Color(0xE69F00) : elder ? new Color(0x8A4B00) : new Color(0xE08E0B); }
        static Color muted()     { return new Color(0x8A94A6); }
    }

    static final Map<String, Font> BASE_FONTS = new HashMap<>();
    static void applyFonts(boolean elder) {
        if (BASE_FONTS.isEmpty()) {
            for (Object k : Collections.list(UIManager.getDefaults().keys())) {
                if (k instanceof String s && s.endsWith(".font")) {
                    Font f = UIManager.getFont(s);
                    if (f != null) BASE_FONTS.put(s, f);
                }
            }
        }
        float scale = elder ? 1.35f : 1f;
        BASE_FONTS.forEach((k, f) -> UIManager.put(k, new FontUIResource(f.deriveFont(f.getSize2D() * scale))));
    }
    static Font font(int style, float delta) {
        Font b = UIManager.getFont("Label.font");
        return b.deriveFont(style, b.getSize2D() + delta);
    }
    static JLabel label(String t, int style, float delta, Color c) {
        JLabel l = new JLabel(t);
        l.setFont(font(style, delta));
        l.setForeground(c);
        return l;
    }

    // =====================================================================
    //  SMALL CUSTOM COMPONENTS
    // =====================================================================
    static class RoundButton extends JButton {
        Color base; boolean hover;
        RoundButton(String text, Color base) {
            super(text);
            this.base = base;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false); setOpaque(false);
            setFont(font(Font.BOLD, 0));
            setBorder(new EmptyBorder(8, 16, 8, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        void setBase(Color c) { base = c; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = !isEnabled() ? Color.GRAY : hover ? base.darker() : base;
            g2.setColor(c);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
            g2.dispose();
            double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
            setForeground(lum > 150 ? Color.BLACK : Color.WHITE);
            super.paintComponent(g);
        }
    }

    /** Scroll-friendly panel that always matches the viewport width. */
    static class VPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static BufferedImage pillIcon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.rotate(Math.toRadians(-40), s / 2.0, s / 2.0);
        int w = (int) (s * 0.9), h = (int) (s * 0.42), x = (s - w) / 2, y = (s - h) / 2;
        Shape cap = new RoundRectangle2D.Float(x, y, w, h, h, h);
        g.setClip(cap);
        g.setColor(new Color(0xE8505B)); g.fillRect(x, y, w / 2, h);
        g.setColor(Color.WHITE); g.fillRect(x + w / 2, y, w - w / 2, h);
        g.setClip(null);
        g.setColor(new Color(0, 0, 0, 100));
        g.setStroke(new BasicStroke(Math.max(1f, s / 32f)));
        g.draw(cap);
        g.dispose();
        return img;
    }

    // =====================================================================
    //  APPLICATION STATE
    // =====================================================================
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("hh:mm a");
    private Data data = Store.load();
    private final Map<String, LocalDateTime> snoozed = new HashMap<>();
    private boolean alerting;
    private TrayIcon tray;
    private final CardLayout cards = new CardLayout();
    private JPanel cardHost;
    private String currentCard = "today";
    private TodayPanel todayPanel;
    private MedsPanel medsPanel;
    private ChartPanel chartPanel;
    private ReportPanel reportPanel;

    public MedMate() {
        super("MedMate - Medicine Schedule & Safety Checker");
        Theme.elder = data.elder; Theme.cb = data.colorBlind;
        applyFonts(Theme.elder);
        setIconImage(pillIcon(64));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { onClose(); }
        });
        setMinimumSize(new Dimension(920, 600));
        setSize(1180, 740);
        setLocationRelativeTo(null);
        setupTray();
        buildUI();
        autoMiss(LocalDateTime.now());
        refreshAll();
        new javax.swing.Timer(30_000, e -> tick()).start();      // reminder clock
        SwingUtilities.invokeLater(this::tick);
    }

    // ----- system tray (AWT) -----
    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open MedMate");
            open.addActionListener(e -> showWindow());
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> exitApp());
            menu.add(open); menu.add(exit);
            tray = new TrayIcon(pillIcon(32), "MedMate", menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> showWindow());
            SystemTray.getSystemTray().add(tray);
        } catch (Exception ex) { tray = null; }
    }
    private void showWindow() { setVisible(true); setExtendedState(NORMAL); toFront(); }
    private void onClose() {
        if (tray != null) {
            setVisible(false);
            tray.displayMessage("MedMate", "Still running in the background to remind you.", TrayIcon.MessageType.INFO);
        } else exitApp();
    }
    private void exitApp() { Store.save(data); System.exit(0); }

    // ----- UI construction -----
    private void buildUI() {
        Container c = getContentPane();
        c.removeAll();
        c.setBackground(Theme.bg());
        c.setLayout(new BorderLayout());
        todayPanel = new TodayPanel();
        medsPanel = new MedsPanel();
        chartPanel = new ChartPanel();
        reportPanel = new ReportPanel();
        cardHost = new JPanel(cards);
        cardHost.setBackground(Theme.bg());
        cardHost.add(todayPanel, "today");
        cardHost.add(medsPanel, "meds");
        cardHost.add(chartPanel, "chart");
        cardHost.add(reportPanel, "report");
        c.add(buildTopBar(), BorderLayout.NORTH);
        c.add(cardHost, BorderLayout.CENTER);
        cards.show(cardHost, currentCard);
        c.revalidate(); c.repaint();
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBackground(Theme.accent());
        bar.setBorder(new EmptyBorder(10, 18, 10, 18));
        JLabel title = label("MedMate", Font.BOLD, 8, Color.WHITE);
        title.setIcon(new ImageIcon(pillIcon(32)));
        title.setIconTextGap(10);

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        nav.setOpaque(false);
        String[][] items = {{"today", "Today"}, {"meds", "Medicines"}, {"chart", "Adherence"}, {"report", "Caregiver Report"}};
        Map<String, RoundButton> buttons = new LinkedHashMap<>();
        for (String[] it : items) {
            RoundButton b = new RoundButton(it[1], it[0].equals(currentCard) ? Theme.highlight() : Theme.accent().darker());
            b.addActionListener(e -> {
                currentCard = it[0];
                cards.show(cardHost, it[0]);
                buttons.forEach((k, v) -> v.setBase(k.equals(currentCard) ? Theme.highlight() : Theme.accent().darker()));
                refreshAll();
            });
            buttons.put(it[0], b);
            nav.add(b);
        }

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        opts.setOpaque(false);
        RoundButton elder = new RoundButton("Large text: " + (Theme.elder ? "ON" : "OFF"),
                Theme.elder ? Theme.highlight() : Theme.accent().darker());
        elder.addActionListener(e -> {
            data.elder = !data.elder; Theme.elder = data.elder;
            applyFonts(Theme.elder); Store.save(data); buildUI(); refreshAll();
        });
        RoundButton cbBtn = new RoundButton("Colour-blind: " + (Theme.cb ? "ON" : "OFF"),
                Theme.cb ? Theme.highlight() : Theme.accent().darker());
        cbBtn.addActionListener(e -> {
            data.colorBlind = !data.colorBlind; Theme.cb = data.colorBlind;
            Store.save(data); buildUI(); refreshAll();
        });
        opts.add(elder); opts.add(cbBtn);

        bar.add(title, BorderLayout.WEST);
        bar.add(nav, BorderLayout.CENTER);
        bar.add(opts, BorderLayout.EAST);
        return bar;
    }

    private void refreshAll() {
        todayPanel.refresh(); medsPanel.refresh(); chartPanel.refresh(); reportPanel.refresh();
    }

    // =====================================================================
    //  SCHEDULING LOGIC
    // =====================================================================
    private LogEntry findLog(String medId, LocalDate d, LocalTime t) {
        for (LogEntry l : data.logs)
            if (l.medId.equals(medId) && l.date.equals(d) && l.time.equals(t)) return l;
        return null;
    }

    private List<Dose> dosesFor(LocalDate date) {
        List<Dose> out = new ArrayList<>();
        for (Medicine m : data.meds) {
            if (!m.scheduledOn(date.getDayOfWeek())) continue;
            for (LocalTime t : m.times) {
                if (LocalDateTime.of(date, t).isBefore(m.created.truncatedTo(ChronoUnit.MINUTES))) continue;
                Dose d = new Dose(m, date, t);
                d.log = findLog(m.id, date, t);
                out.add(d);
            }
        }
        out.sort(Comparator.comparing(x -> x.time));
        return out;
    }

    /** Doses with no response 2 hours after their time are recorded as MISSED. */
    private void autoMiss(LocalDateTime now) {
        boolean changed = false;
        for (int i = 0; i < 7; i++) {
            for (Dose d : dosesFor(now.toLocalDate().minusDays(i))) {
                if (d.log == null && d.due().plusHours(2).isBefore(now)) {
                    data.logs.add(new LogEntry(d.date, d.med, d.time, Status.MISSED));
                    changed = true;
                }
            }
        }
        if (changed) Store.save(data);
    }

    private void setStatus(Dose d, Status st) {
        LogEntry old = findLog(d.med.id, d.date, d.time);
        if (old != null) {
            if (old.status == Status.TAKEN) d.med.stock++;
            data.logs.remove(old);
        }
        if (st != null) {
            data.logs.add(new LogEntry(d.date, d.med, d.time, st));
            if (st == Status.TAKEN) d.med.stock = Math.max(0, d.med.stock - 1);
        }
        snoozed.remove(d.key());
        Store.save(data);
        refreshAll();
    }

    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        autoMiss(now);
        if (!alerting) {
            for (Dose d : dosesFor(now.toLocalDate())) {
                if (d.log != null || d.due().isAfter(now)) continue;
                LocalDateTime sn = snoozed.get(d.key());
                if (sn != null && sn.isAfter(now)) continue;
                refreshAll();
                showAlert(d);
                break;
            }
        }
        refreshAll();
    }

    // ----- big, high-contrast reminder pop-up -----
    private void showAlert(Dose d) {
        alerting = true;
        if (tray != null)
            tray.displayMessage("Time for " + d.med.name, d.med.dosage + " - " + d.time.format(TF), TrayIcon.MessageType.INFO);
        Toolkit.getDefaultToolkit().beep();

        JDialog dlg = new JDialog(this, "Medicine reminder", true);
        dlg.setAlwaysOnTop(true);
        int[] choice = {1};                         // 0 taken, 1 snooze, 2 skip

        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.accent(), 5), new EmptyBorder(22, 30, 22, 30)));
        JPanel txt = new JPanel(new GridLayout(0, 1, 0, 6));
        txt.setOpaque(false);
        txt.add(label("Time to take your medicine", Font.PLAIN, 4, Theme.sub()));
        txt.add(label(d.med.name, Font.BOLD, 16, Theme.text()));
        txt.add(label(d.med.dosage + "   |   scheduled " + d.time.format(TF), Font.PLAIN, 4, Theme.text()));
        if (!d.med.notes.isBlank()) txt.add(label("Note: " + d.med.notes, Font.ITALIC, 2, Theme.sub()));
        p.add(txt, BorderLayout.CENTER);

        JPanel btns = new JPanel(new GridLayout(1, 3, 12, 0));
        btns.setOpaque(false);
        RoundButton taken = new RoundButton("Taken", Theme.ok());
        RoundButton snooze = new RoundButton("Snooze 10 min", Theme.warn());
        RoundButton skip = new RoundButton("Skip", Theme.muted());
        for (RoundButton b : new RoundButton[]{taken, snooze, skip}) {
            b.setFont(font(Font.BOLD, 4));
            b.setBorder(new EmptyBorder(14, 20, 14, 20));
        }
        taken.addActionListener(e -> { choice[0] = 0; dlg.dispose(); });
        snooze.addActionListener(e -> { choice[0] = 1; dlg.dispose(); });
        skip.addActionListener(e -> { choice[0] = 2; dlg.dispose(); });
        btns.add(taken); btns.add(snooze); btns.add(skip);
        p.add(btns, BorderLayout.SOUTH);

        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(null);
        dlg.setVisible(true);                       // blocks until answered

        alerting = false;
        if (choice[0] == 0) setStatus(d, Status.TAKEN);
        else if (choice[0] == 2) setStatus(d, Status.SKIPPED);
        else snoozed.put(d.key(), LocalDateTime.now().plusMinutes(10));
    }

    // =====================================================================
    //  TODAY PANEL  (timeline of dose cards)
    // =====================================================================
    class TodayPanel extends JPanel {
        final JLabel dateLbl = label("", Font.BOLD, 8, Theme.text());
        final JLabel clockLbl = label("", Font.PLAIN, 4, Theme.sub());
        final JLabel summary = label("", Font.PLAIN, 2, Theme.text());
        final JProgressBar bar = new JProgressBar(0, 100);
        final JPanel banner = new JPanel();
        final VPanel body = new VPanel();

        TodayPanel() {
            setLayout(new BorderLayout(0, 12));
            setBackground(Theme.bg());
            setBorder(new EmptyBorder(16, 20, 16, 20));
            JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            top.setOpaque(false);
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.add(dateLbl, BorderLayout.WEST);
            row.add(clockLbl, BorderLayout.EAST);
            bar.setForeground(Theme.ok());
            bar.setPreferredSize(new Dimension(100, 12));
            banner.setLayout(new BoxLayout(banner, BoxLayout.Y_AXIS));
            banner.setOpaque(false);
            top.add(row); top.add(Box.createVerticalStrut(6));
            top.add(summary); top.add(Box.createVerticalStrut(4));
            top.add(bar); top.add(Box.createVerticalStrut(8));
            top.add(banner);
            for (Component comp : top.getComponents()) ((JComponent) comp).setAlignmentX(0f);
            add(top, BorderLayout.NORTH);

            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            JScrollPane sp = new JScrollPane(body);
            sp.setBorder(null);
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
            sp.getVerticalScrollBar().setUnitIncrement(16);
            add(sp, BorderLayout.CENTER);
        }

        void refresh() {
            LocalDate today = LocalDate.now();
            dateLbl.setText(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")));
            clockLbl.setText(LocalTime.now().format(TF));
            List<Dose> doses = dosesFor(today);
            long done = doses.stream().filter(d -> d.log != null && d.log.status == Status.TAKEN).count();
            summary.setText(doses.isEmpty() ? "No medicines scheduled today"
                    : done + " of " + doses.size() + " doses taken today");
            bar.setValue(doses.isEmpty() ? 0 : (int) (done * 100 / doses.size()));
            bar.setForeground(Theme.ok());

            banner.removeAll();
            for (Medicine m : data.meds)
                if (m.stock <= 5) {
                    JLabel l = label("Refill needed: " + m.name + " - only " + m.stock + " left", Font.BOLD, 0, Theme.bad());
                    l.setAlignmentX(0f);
                    banner.add(l);
                }

            body.removeAll();
            if (doses.isEmpty()) {
                body.add(label("Nothing scheduled today. Add medicines in the Medicines tab.", Font.PLAIN, 2, Theme.sub()));
            } else if (Theme.elder) {                       // simplified single-column layout
                for (Dose d : doses) {
                    DoseCard c = new DoseCard(d);
                    body.add(c);
                    body.add(Box.createVerticalStrut(10));
                }
            } else {                                        // four time-of-day columns
                JPanel grid = new JPanel(new GridLayout(1, 4, 14, 0));
                grid.setOpaque(false);
                for (Slot s : Slot.values()) {
                    JPanel col = new JPanel(new BorderLayout());
                    col.setOpaque(false);
                    JPanel inner = new JPanel();
                    inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
                    inner.setOpaque(false);
                    JPanel head = new JPanel(new BorderLayout());
                    head.setBackground(s.color);
                    head.setBorder(new EmptyBorder(8, 12, 8, 12));
                    head.add(label(s.title, Font.BOLD, 2, Color.WHITE), BorderLayout.WEST);
                    inner.add(head);
                    inner.add(Box.createVerticalStrut(8));
                    boolean any = false;
                    for (Dose d : doses) {
                        if (Slot.of(d.time) != s) continue;
                        any = true;
                        inner.add(new DoseCard(d));
                        inner.add(Box.createVerticalStrut(8));
                    }
                    if (!any) {
                        JLabel none = label("Nothing due", Font.ITALIC, 0, Theme.muted());
                        none.setAlignmentX(0.5f);
                        inner.add(none);
                    }
                    col.add(inner, BorderLayout.NORTH);
                    grid.add(col);
                }
                body.add(grid);
            }
            body.revalidate(); body.repaint(); revalidate(); repaint();
        }
    }

    /** Custom-painted rounded card for a single dose. */
    class DoseCard extends JPanel {
        final Color stripe;
        DoseCard(Dose d) {
            setOpaque(false);
            setLayout(new BorderLayout(10, 6));
            setBorder(new EmptyBorder(10, 18, 14, 12));

            boolean overdue = d.log == null && !d.due().isAfter(LocalDateTime.now());
            String statusText; Color sc;
            if (d.log == null) {
                statusText = overdue ? "Due now" : "Upcoming";
                sc = overdue ? Theme.warn() : Theme.accent();
            } else switch (d.log.status) {
                case TAKEN -> { statusText = "Taken"; sc = Theme.ok(); }
                case MISSED -> { statusText = "Missed"; sc = Theme.bad(); }
                default -> { statusText = "Skipped"; sc = Theme.muted(); }
            }
            stripe = sc;

            JPanel mid = new JPanel();
            mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
            mid.setOpaque(false);
            mid.add(label(d.time.format(TF), Font.BOLD, 3, Theme.text()));
            mid.add(label(d.med.name, Font.BOLD, 1, Theme.text()));
            mid.add(label(d.med.dosage, Font.PLAIN, 0, Theme.sub()));
            mid.add(label(statusText, Font.BOLD, 0, sc));
            add(mid, BorderLayout.CENTER);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            btns.setOpaque(false);
            if (d.log == null) {
                RoundButton ok = new RoundButton("Taken", Theme.ok());
                RoundButton skip = new RoundButton("Skip", Theme.muted());
                ok.addActionListener(e -> setStatus(d, Status.TAKEN));
                skip.addActionListener(e -> setStatus(d, Status.SKIPPED));
                btns.add(ok); btns.add(skip);
            } else {
                RoundButton undo = new RoundButton("Undo", Theme.muted());
                undo.addActionListener(e -> setStatus(d, null));
                btns.add(undo);
            }
            add(btns, BorderLayout.SOUTH);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(0, 0, 0, 20));
            g2.fill(new RoundRectangle2D.Float(1, 3, w - 2, h - 3, 16, 16));
            Shape card = new RoundRectangle2D.Float(0, 0, w - 1, h - 4, 16, 16);
            g2.setColor(Theme.card());
            g2.fill(card);
            g2.setClip(card);
            g2.setColor(stripe);
            g2.fillRect(0, 0, 8, h);
            g2.setClip(null);
            g2.setColor(Theme.elder ? Color.BLACK : new Color(0xDDE2EA));
            g2.setStroke(new BasicStroke(Theme.elder ? 2f : 1f));
            g2.draw(card);
            g2.dispose();
        }
    }

    // =====================================================================
    //  MEDICINES PANEL  (JTable + add / edit / delete)
    // =====================================================================
    class MedsPanel extends JPanel {
        final DefaultTableModel model = new DefaultTableModel(
                new String[]{"Medicine", "Dosage", "Times", "Days", "Pills left"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        final JTable table = new JTable(model);

        MedsPanel() {
            setLayout(new BorderLayout(0, 12));
            setBackground(Theme.bg());
            setBorder(new EmptyBorder(16, 20, 16, 20));
            add(label("My Medicines", Font.BOLD, 8, Theme.text()), BorderLayout.NORTH);

            table.setRowHeight(Math.round(table.getFont().getSize2D() * 2.3f));
            table.getTableHeader().setFont(font(Font.BOLD, 0));
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                         boolean foc, int row, int col) {
                    super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                    setBorder(new EmptyBorder(0, 10, 0, 10));
                    if (!sel) {
                        setBackground(row % 2 == 0 ? Color.WHITE : new Color(0xEEF2F8));
                        setForeground(Theme.text());
                    }
                    if (col == 4 && row < data.meds.size() && data.meds.get(row).stock <= 5) {
                        setFont(font(Font.BOLD, 0));
                        if (!sel) setForeground(Theme.bad());
                    } else setFont(font(Font.PLAIN, 0));
                    return this;
                }
            });
            JScrollPane sp = new JScrollPane(table);
            sp.getViewport().setBackground(Color.WHITE);
            add(sp, BorderLayout.CENTER);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            btns.setOpaque(false);
            RoundButton add = new RoundButton("Add medicine", Theme.ok());
            RoundButton edit = new RoundButton("Edit", Theme.accent());
            RoundButton del = new RoundButton("Delete", Theme.bad());
            RoundButton chk = new RoundButton("Check all interactions", Theme.warn());
            add.addActionListener(e -> {
                Medicine m = editMedicine(null);
                if (m != null) { data.meds.add(m); Store.save(data); refreshAll(); }
            });
            edit.addActionListener(e -> {
                int r = table.getSelectedRow();
                if (r < 0) { JOptionPane.showMessageDialog(MedMate.this, "Select a medicine first."); return; }
                if (editMedicine(data.meds.get(r)) != null) { Store.save(data); refreshAll(); }
            });
            del.addActionListener(e -> {
                int r = table.getSelectedRow();
                if (r < 0) { JOptionPane.showMessageDialog(MedMate.this, "Select a medicine first."); return; }
                Medicine m = data.meds.get(r);
                if (JOptionPane.showConfirmDialog(MedMate.this, "Delete " + m.name + "?", "Confirm",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    data.meds.remove(r); Store.save(data); refreshAll();
                }
            });
            chk.addActionListener(e -> {
                List<String> w = Interactions.checkAll(data.meds);
                JOptionPane.showMessageDialog(MedMate.this,
                        w.isEmpty() ? "No known interactions found among your medicines.\n(Built-in list is limited - always ask a pharmacist.)"
                                : "Possible interactions:\n\n" + String.join("\n\n", w) + "\n\nPlease ask your doctor or pharmacist.",
                        "Interaction check", w.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            });
            btns.add(add); btns.add(edit); btns.add(del); btns.add(chk);
            add(btns, BorderLayout.SOUTH);
        }

        void refresh() {
            int sel = table.getSelectedRow();
            model.setRowCount(0);
            for (Medicine m : data.meds) {
                StringBuilder times = new StringBuilder();
                for (LocalTime t : m.times) times.append(times.length() > 0 ? ", " : "").append(t.format(TF));
                model.addRow(new Object[]{m.name, m.dosage, times.toString(), daysText(m),
                        m.stock + (m.stock <= 5 ? "  (refill soon)" : "")});
            }
            if (sel >= 0 && sel < model.getRowCount()) table.setRowSelectionInterval(sel, sel);
        }
    }

    static String daysText(Medicine m) {
        int n = 0; StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) if (m.days[i]) {
            n++;
            sb.append(sb.length() > 0 ? ", " : "").append(DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
        }
        return n == 7 ? "Every day" : sb.toString();
    }

    /** Add/edit dialog. Returns the saved medicine, or null if cancelled. */
    private Medicine editMedicine(Medicine existing) {
        JDialog dlg = new JDialog(this, existing == null ? "Add medicine" : "Edit medicine", true);
        JTextField name = new JTextField(existing == null ? "" : existing.name, 22);
        JTextField dosage = new JTextField(existing == null ? "" : existing.dosage, 22);
        JTextField notes = new JTextField(existing == null ? "" : existing.notes, 22);
        JSpinner stock = new JSpinner(new SpinnerNumberModel(existing == null ? 30 : existing.stock, 0, 999, 1));

        DefaultListModel<LocalTime> tm = new DefaultListModel<>();
        if (existing != null) existing.times.stream().sorted().forEach(tm::addElement);
        JList<LocalTime> tl = new JList<>(tm);
        tl.setVisibleRowCount(3);
        tl.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                setText(((LocalTime) v).format(TF));
                return this;
            }
        });
        JSpinner timeSp = new JSpinner(new SpinnerDateModel());
        timeSp.setEditor(new JSpinner.DateEditor(timeSp, "hh:mm a"));
        RoundButton addT = new RoundButton("Add time", Theme.accent());
        RoundButton remT = new RoundButton("Remove", Theme.muted());
        addT.addActionListener(e -> {
            LocalTime t = ((Date) timeSp.getValue()).toInstant().atZone(ZoneId.systemDefault())
                    .toLocalTime().withSecond(0).withNano(0);
            if (!tm.contains(t)) {
                List<LocalTime> all = new ArrayList<>(Collections.list(tm.elements()));
                all.add(t); Collections.sort(all);
                tm.clear(); all.forEach(tm::addElement);
            }
        });
        remT.addActionListener(e -> { if (tl.getSelectedIndex() >= 0) tm.remove(tl.getSelectedIndex()); });
        JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        timeRow.add(timeSp); timeRow.add(addT); timeRow.add(remT);
        JPanel timesBox = new JPanel(new BorderLayout(0, 4));
        timesBox.add(timeRow, BorderLayout.NORTH);
        timesBox.add(new JScrollPane(tl), BorderLayout.CENTER);

        JPanel daysRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JCheckBox[] dayBoxes = new JCheckBox[7];
        for (int i = 0; i < 7; i++) {
            dayBoxes[i] = new JCheckBox(DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    existing == null || existing.days[i]);
            daysRow.add(dayBoxes[i]);
        }

        JTextArea warn = new JTextArea(3, 30);
        warn.setEditable(false); warn.setLineWrap(true); warn.setWrapStyleWord(true);
        warn.setOpaque(false); warn.setForeground(Theme.bad()); warn.setFont(font(Font.BOLD, 0));
        List<Medicine> others = new ArrayList<>(data.meds);
        if (existing != null) others.remove(existing);
        Runnable live = () -> warn.setText(String.join("\n", Interactions.check(name.getText(), others)));
        name.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { live.run(); }
            public void removeUpdate(DocumentEvent e) { live.run(); }
            public void changedUpdate(DocumentEvent e) { live.run(); }
        });
        live.run();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(14, 14, 6, 14));
        addRow(form, 0, "Name", name);
        addRow(form, 1, "Dosage (e.g. 1 tablet, 5 ml)", dosage);
        addRow(form, 2, "Times per day", timesBox);
        addRow(form, 3, "Days", daysRow);
        addRow(form, 4, "Pills left", stock);
        addRow(form, 5, "Notes (e.g. after food)", notes);
        addRow(form, 6, "Safety check", warn);

        Medicine[] result = {null};
        RoundButton save = new RoundButton("Save", Theme.ok());
        RoundButton cancel = new RoundButton("Cancel", Theme.muted());
        cancel.addActionListener(e -> dlg.dispose());
        save.addActionListener(e -> {
            if (name.getText().isBlank()) { JOptionPane.showMessageDialog(dlg, "Please enter a medicine name."); return; }
            if (tm.isEmpty()) { JOptionPane.showMessageDialog(dlg, "Add at least one time of day."); return; }
            boolean anyDay = false;
            for (JCheckBox b : dayBoxes) anyDay |= b.isSelected();
            if (!anyDay) { JOptionPane.showMessageDialog(dlg, "Select at least one day."); return; }
            List<String> w = Interactions.check(name.getText(), others);
            if (!w.isEmpty()) {
                int r = JOptionPane.showOptionDialog(dlg,
                        "Possible interaction found:\n\n" + String.join("\n\n", w) + "\n\nPlease check with your doctor or pharmacist.",
                        "Interaction warning", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null,
                        new String[]{"Save anyway", "Cancel"}, "Cancel");
                if (r != 0) return;
            }
            Medicine m = existing != null ? existing : new Medicine();
            m.name = name.getText().trim(); m.dosage = dosage.getText().trim(); m.notes = notes.getText().trim();
            m.stock = (Integer) stock.getValue();
            m.times = new ArrayList<>(Collections.list(tm.elements()));
            for (int i = 0; i < 7; i++) m.days[i] = dayBoxes[i].isSelected();
            result[0] = m;
            dlg.dispose();
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        south.add(cancel); south.add(save);

        dlg.add(form, BorderLayout.CENTER);
        dlg.add(south, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        return result[0];
    }

    static void addRow(JPanel p, int y, String lbl, Component c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.anchor = GridBagConstraints.NORTHWEST; g.insets = new Insets(6, 6, 6, 12);
        p.add(new JLabel(lbl), g);
        g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(6, 0, 6, 6);
        p.add(c, g);
    }

    // =====================================================================
    //  ADHERENCE CHART  (custom Graphics2D)
    // =====================================================================
    /** returns {taken, skipped, missed} for a date, from logs. */
    private int[] countsFor(LocalDate d) {
        int[] c = new int[3];
        for (LogEntry l : data.logs) if (l.date.equals(d)) c[l.status == Status.TAKEN ? 0 : l.status == Status.SKIPPED ? 1 : 2]++;
        return c;
    }

    class ChartPanel extends JPanel {
        final JLabel head = label("", Font.BOLD, 8, Theme.text());
        final JLabel sub = label("", Font.PLAIN, 2, Theme.sub());
        final BarChart chart = new BarChart();
        ChartPanel() {
            setLayout(new BorderLayout(0, 10));
            setBackground(Theme.bg());
            setBorder(new EmptyBorder(16, 20, 16, 20));
            JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
            top.setOpaque(false);
            top.add(head); top.add(sub);
            add(top, BorderLayout.NORTH);
            add(chart, BorderLayout.CENTER);
        }
        void refresh() {
            int t = 0, s = 0, m = 0;
            for (int i = 0; i < 7; i++) {
                int[] c = countsFor(LocalDate.now().minusDays(i));
                t += c[0]; s += c[1]; m += c[2];
            }
            int total = t + s + m;
            head.setText("Weekly adherence: " + (total == 0 ? "no data yet" : (t * 100 / total) + "%"));
            sub.setText(t + " taken   |   " + m + " missed   |   " + s + " skipped   (last 7 days)");
            chart.repaint();
        }
    }

    class BarChart extends JPanel {
        BarChart() { setBackground(Color.WHITE); setBorder(BorderFactory.createLineBorder(new Color(0xDDE2EA))); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int left = 56, right = 24, top = 46, bottom = 56;
            int cw = w - left - right, ch = h - top - bottom;
            if (cw < 50 || ch < 50) { g2.dispose(); return; }

            int[][] counts = new int[7][];
            int max = 2;
            for (int i = 0; i < 7; i++) {
                counts[i] = countsFor(LocalDate.now().minusDays(6 - i));
                max = Math.max(max, counts[i][0] + counts[i][1] + counts[i][2]);
            }
            if (max % 2 == 1) max++;

            g2.setFont(font(Font.PLAIN, 0));
            FontMetrics fm = g2.getFontMetrics();
            // gridlines + y labels
            int ticks = Math.min(max, 6);
            for (int i = 0; i <= ticks; i++) {
                int y = top + ch - ch * i / ticks;
                g2.setColor(new Color(0xE5E9F0));
                g2.drawLine(left, y, left + cw, y);
                g2.setColor(Theme.sub());
                String s = String.valueOf(max * i / ticks);
                g2.drawString(s, left - 10 - fm.stringWidth(s), y + fm.getAscent() / 2 - 1);
            }
            g2.setColor(Theme.text());
            g2.drawLine(left, top, left, top + ch);
            g2.drawLine(left, top + ch, left + cw, top + ch);

            int slot = cw / 7, bw = (int) (slot * 0.5);
            Color[] cols = {Theme.ok(), Theme.muted(), Theme.bad()};
            for (int i = 0; i < 7; i++) {
                int x = left + i * slot + (slot - bw) / 2;
                int yBase = top + ch;
                for (int k = 0; k < 3; k++) {
                    int hh = (int) Math.round(ch * counts[i][k] / (double) max);
                    if (hh <= 0) continue;
                    g2.setColor(cols[k]);
                    g2.fillRect(x, yBase - hh, bw, hh);
                    if (hh >= fm.getHeight() + 2) {
                        g2.setColor(Color.WHITE);
                        String s = String.valueOf(counts[i][k]);
                        g2.drawString(s, x + (bw - fm.stringWidth(s)) / 2, yBase - hh / 2 + fm.getAscent() / 2 - 1);
                    }
                    yBase -= hh;
                }
                LocalDate d = LocalDate.now().minusDays(6 - i);
                String l1 = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                String l2 = d.format(DateTimeFormatter.ofPattern("d MMM"));
                g2.setColor(i == 6 ? Theme.accent() : Theme.text());
                g2.setFont(font(i == 6 ? Font.BOLD : Font.PLAIN, 0));
                FontMetrics f2 = g2.getFontMetrics();
                int cx = x + bw / 2;
                g2.drawString(l1, cx - f2.stringWidth(l1) / 2, top + ch + f2.getHeight());
                g2.drawString(l2, cx - f2.stringWidth(l2) / 2, top + ch + 2 * f2.getHeight() - 2);
            }

            // legend
            g2.setFont(font(Font.PLAIN, 0));
            fm = g2.getFontMetrics();
            String[] names = {"Taken", "Skipped", "Missed"};
            int lx = left;
            for (int k = 0; k < 3; k++) {
                g2.setColor(cols[k]);
                g2.fillRoundRect(lx, 14, 16, 16, 4, 4);
                g2.setColor(Theme.text());
                g2.drawString(names[k], lx + 22, 14 + fm.getAscent());
                lx += 22 + fm.stringWidth(names[k]) + 26;
            }
            g2.dispose();
        }
    }

    // =====================================================================
    //  CAREGIVER REPORT  (printable / savable text)
    // =====================================================================
    class ReportPanel extends JPanel {
        final JTextArea area = new JTextArea();
        ReportPanel() {
            setLayout(new BorderLayout(0, 12));
            setBackground(Theme.bg());
            setBorder(new EmptyBorder(16, 20, 16, 20));
            add(label("Caregiver Report", Font.BOLD, 8, Theme.text()), BorderLayout.NORTH);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(UIManager.getFont("Label.font").getSize2D())));
            area.setMargin(new Insets(12, 14, 12, 14));
            add(new JScrollPane(area), BorderLayout.CENTER);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            btns.setOpaque(false);
            RoundButton print = new RoundButton("Print", Theme.accent());
            RoundButton save = new RoundButton("Save as text file", Theme.ok());
            print.addActionListener(e -> {
                try { area.print(new java.text.MessageFormat("MedMate Weekly Report"), new java.text.MessageFormat("Page {0}")); }
                catch (Exception ex) { JOptionPane.showMessageDialog(MedMate.this, "Could not print: " + ex.getMessage()); }
            });
            save.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new File("MedMate-report-" + LocalDate.now() + ".txt"));
                if (fc.showSaveDialog(MedMate.this) == JFileChooser.APPROVE_OPTION) {
                    try { java.nio.file.Files.writeString(fc.getSelectedFile().toPath(), area.getText()); }
                    catch (IOException ex) { JOptionPane.showMessageDialog(MedMate.this, "Could not save: " + ex.getMessage()); }
                }
            });
            btns.add(print); btns.add(save);
            add(btns, BorderLayout.SOUTH);
        }

        void refresh() { area.setText(buildReport()); area.setCaretPosition(0); }
    }

    private String buildReport() {
        LocalDate to = LocalDate.now(), from = to.minusDays(6);
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd MMM yyyy");
        List<LogEntry> logs = new ArrayList<>();
        for (LogEntry l : data.logs) if (!l.date.isBefore(from) && !l.date.isAfter(to)) logs.add(l);
        logs.sort(Comparator.comparing((LogEntry l) -> l.date).thenComparing(l -> l.time));

        int t = 0, s = 0, m = 0;
        for (LogEntry l : logs) { if (l.status == Status.TAKEN) t++; else if (l.status == Status.SKIPPED) s++; else m++; }
        int total = t + s + m;

        StringBuilder sb = new StringBuilder();
        sb.append("MEDMATE - WEEKLY ADHERENCE REPORT\n");
        sb.append("Period: ").append(from.format(df)).append(" to ").append(to.format(df)).append("\n");
        sb.append("=".repeat(64)).append("\n\n");
        sb.append("Overall adherence: ").append(total == 0 ? "n/a" : (t * 100 / total) + "%")
          .append("   (").append(t).append(" taken, ").append(m).append(" missed, ").append(s).append(" skipped)\n\n");

        sb.append("PER MEDICINE\n").append("-".repeat(64)).append("\n");
        sb.append(String.format("%-24s %6s %7s %8s %10s%n", "Medicine", "Taken", "Missed", "Skipped", "Adherence"));
        Map<String, int[]> per = new LinkedHashMap<>();
        for (LogEntry l : logs) {
            int[] c = per.computeIfAbsent(l.medName, k -> new int[3]);
            c[l.status == Status.TAKEN ? 0 : l.status == Status.MISSED ? 1 : 2]++;
        }
        if (per.isEmpty()) sb.append("(no doses recorded yet)\n");
        per.forEach((n, c) -> {
            int tot = c[0] + c[1] + c[2];
            sb.append(String.format("%-24s %6d %7d %8d %9d%%%n", n.length() > 24 ? n.substring(0, 24) : n,
                    c[0], c[1], c[2], tot == 0 ? 0 : c[0] * 100 / tot));
        });

        sb.append("\nDAY BY DAY\n").append("-".repeat(64)).append("\n");
        if (logs.isEmpty()) sb.append("(nothing recorded)\n");
        for (LogEntry l : logs)
            sb.append(String.format("%-12s %-9s %-24s %s%n",
                    l.date.format(DateTimeFormatter.ofPattern("EEE dd MMM")), l.time.format(TF), l.medName, l.status));

        sb.append("\nLOW STOCK (5 or fewer pills)\n").append("-".repeat(64)).append("\n");
        boolean low = false;
        for (Medicine md : data.meds) if (md.stock <= 5) { sb.append(md.name).append(": ").append(md.stock).append(" left\n"); low = true; }
        if (!low) sb.append("None\n");

        sb.append("\nPOSSIBLE INTERACTIONS\n").append("-".repeat(64)).append("\n");
        List<String> w = Interactions.checkAll(data.meds);
        if (w.isEmpty()) sb.append("None found in the built-in list.\n");
        else w.forEach(x -> sb.append("* ").append(x).append("\n"));

        sb.append("\nNote: MedMate's interaction list is small and for awareness only.\n")
          .append("Always confirm with a doctor or pharmacist.\n");
        return sb.toString();
    }
    public static void main(String[] args) {
        System.setProperty("swing.boldMetal", "false");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new MedMate().setVisible(true);
        });
    }
}