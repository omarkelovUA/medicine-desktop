package ua.medicine.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import ua.medicine.Settings;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Person;
import ua.medicine.i18n.Messages;
import ua.medicine.schedule.TimeScale;
import ua.medicine.schedule.TimeScale.Column;
import ua.medicine.schedule.TimeScale.Zoom;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Gantt-style dashboard: one bar per medicine course on a zoomable time axis. */
final class TimelineView extends VBox {

    private static final double NAME_WIDTH = 180;
    private static final double ROW_HEIGHT = 34;
    private static final double GROUP_HEIGHT = 30;
    private static final double HEADER_HEIGHT = 40;
    /** Below this column width a wrapped week label needs a third line, so the compact one is used. */
    private static final double COMPACT_BELOW = 56;

    private final Settings settings;
    private final Consumer<Medicine> onOpen;
    private final Label range = new Label();
    private final VBox rows = new VBox();
    private final ScrollPane scroll = new ScrollPane(rows);
    private Node header;
    private boolean rendered;
    private TimeScale scale;
    private List<Person> persons = List.of();
    private List<Medicine> medicines = List.of();
    private boolean grouped;
    private LocalDate today;

    TimelineView(Settings settings, LocalDate today, Consumer<Medicine> onOpen) {
        this.settings = settings;
        this.onOpen = onOpen;
        this.today = today;
        scale = TimeScale.around(settings.timelineZoom(), today);
        getStyleClass().add("timeline");
        range.getStyleClass().add("timeline-range");

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("cards-scroll", "timeline-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        // the column header stays in the rows (so its width matches them) but is pinned to the top on scroll
        scroll.vvalueProperty().addListener(o -> pinHeader());
        scroll.viewportBoundsProperty().addListener(o -> pinHeader());
        rows.heightProperty().addListener(o -> pinHeader());
        getChildren().addAll(toolbar(), scroll);
    }

    void show(List<Person> persons, List<Medicine> medicines, boolean grouped, LocalDate today) {
        TimeScale followed = scale.followToday(this.today, today); // a new day moves the view unless navigated away
        if (rendered && followed.equals(scale) && today.equals(this.today) && grouped == this.grouped
                && persons.equals(this.persons) && medicines.equals(this.medicines)) {
            return; // the periodic refresh changed nothing: keep the nodes, so open tooltips and clicks survive
        }
        this.persons = List.copyOf(persons);
        this.medicines = List.copyOf(medicines);
        this.grouped = grouped;
        this.today = today;
        scale = followed;
        render();
    }

    /** Buttons that read as one control; exactly one stays selected. */
    static HBox segmented(List<ToggleButton> buttons, ToggleGroup group) {
        var box = new HBox();
        box.getStyleClass().add("segmented");
        box.setMinWidth(Region.USE_PREF_SIZE);
        for (int i = 0; i < buttons.size(); i++) {
            var b = buttons.get(i);
            b.setToggleGroup(group);
            b.setMinWidth(Region.USE_PREF_SIZE);
            if (i == 0) {
                b.getStyleClass().add("first");
            }
            if (i == buttons.size() - 1) {
                b.getStyleClass().add("last");
            }
            box.getChildren().add(b);
        }
        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });
        return box;
    }

    private HBox toolbar() {
        var group = new ToggleGroup();
        List<ToggleButton> buttons = new ArrayList<>();
        for (Zoom z : Zoom.values()) {
            var b = new ToggleButton(Messages.get("zoom." + z.name()));
            b.setUserData(z);
            b.setSelected(z == scale.zoom());
            buttons.add(b);
        }
        var zooms = segmented(buttons, group);
        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now != null && now.getUserData() != scale.zoom()) {
                Zoom z = (Zoom) now.getUserData();
                scale = scale.withZoom(z);
                settings.setTimelineZoom(z);
                render();
            }
        });

        var prev = new Button(Messages.get("timeline.prev"));
        prev.setOnAction(e -> move(scale.previous()));
        var todayButton = new Button(Messages.get("timeline.today"));
        todayButton.setOnAction(e -> move(TimeScale.around(scale.zoom(), today)));
        var next = new Button(Messages.get("timeline.next"));
        next.setOnAction(e -> move(scale.next()));

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var bar = new HBox(8, zooms, spacer, prev, todayButton, next, range);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("timeline-toolbar");
        return bar;
    }

    private void pinHeader() {
        if (header != null) {
            double hidden = Math.max(0, rows.getHeight() - scroll.getViewportBounds().getHeight());
            header.setTranslateY(scroll.getVvalue() * hidden);
        }
    }

    private void move(TimeScale to) {
        scale = to;
        render();
    }

    private void render() {
        rendered = true;
        range.setText(TimeLabels.range(scale));
        header = header();
        header.setViewOrder(-1); // drawn over the rows it is pinned above
        rows.getChildren().setAll(header);
        pinHeader();
        List<TimelineRows.Row> list = TimelineRows.build(persons, medicines, grouped, scale);
        if (list.isEmpty()) {
            var empty = new Label(Messages.get("empty.noMedicinesInRange"));
            empty.getStyleClass().add("empty-state");
            rows.getChildren().add(empty);
            return;
        }
        for (TimelineRows.Row row : list) {
            rows.getChildren().add(switch (row) {
                case TimelineRows.PersonHeader h -> groupRow(h.person());
                case TimelineRows.MedicineRow m -> medicineRow(m.medicine());
            });
        }
    }

    private HBox header() {
        var track = gridTrack();
        addToday(track); // behind the labels
        List<Column> columns = scale.columns();
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            String full = TimeLabels.column(scale.zoom(), c);
            String compact = TimeLabels.columnCompact(scale.zoom(), c);
            var main = new Label(full);
            main.setWrapText(true);
            main.setTextAlignment(TextAlignment.CENTER);
            main.setMinHeight(Region.USE_PREF_SIZE);
            var head = new VBox(main);
            String subText = TimeLabels.columnSub(scale.zoom(), c, i == 0);
            if (!subText.isEmpty()) { // otherwise the main label may wrap into the second line
                var sub = new Label(subText);
                sub.getStyleClass().add("muted");
                head.getChildren().add(sub);
            }
            head.setAlignment(Pos.CENTER);
            head.getStyleClass().add("timeline-column-head");
            if (!compact.equals(full)) {
                head.widthProperty().addListener((o, old, w) ->
                        main.setText(w.doubleValue() < COMPACT_BELOW ? compact : full));
            }
            track.place(head, scale.fraction(c.start()), scale.fraction(c.endExclusive()), Kind.FILL);
        }
        var row = row(new Region(), track, HEADER_HEIGHT);
        row.getStyleClass().add("timeline-header");
        return row;
    }

    private HBox groupRow(Person person) {
        var nameCell = new HBox(6, new Avatar(person, 9), new Label(person.name()));
        nameCell.setAlignment(Pos.CENTER_LEFT);
        var track = gridTrack(); // keeps the column lines and today line running through the header
        addToday(track);
        var row = row(nameCell, track, GROUP_HEIGHT);
        row.getStyleClass().add("timeline-group");
        return row;
    }

    private HBox medicineRow(Medicine m) {
        var dot = new Circle(5, Color.web(m.color()));
        var name = new Label(m.name());
        name.setMinWidth(0);
        var nameCell = new HBox(6, dot, name);
        nameCell.setAlignment(Pos.CENTER_LEFT);

        var track = gridTrack();
        addToday(track); // behind the bar and its label
        LocalDate endExclusive = m.endDate().map(end -> end.plusDays(1)).orElse(null);
        boolean cutLeft = m.firstStart().isBefore(scale.start());
        boolean cutRight = endExclusive == null || endExclusive.isAfter(scale.endExclusive());
        double from = scale.fraction(m.firstStart());
        double to = endExclusive == null ? 1 : scale.fraction(endExclusive);

        var bar = new Region();
        bar.getStyleClass().add("timeline-bar");
        bar.setStyle("-fx-background-color: " + m.color() + "; -fx-background-radius: "
                + (cutLeft ? 0 : 6) + " " + (cutRight ? 0 : 6) + " " + (cutRight ? 0 : 6) + " " + (cutLeft ? 0 : 6) + ";");
        boolean arrow = endExclusive == null && cutRight;
        var label = new Label(TimeLabels.barLabel(m) + (arrow ? " →" : ""));
        label.getStyleClass().add("timeline-bar-label");
        var tip = TimeLabels.tooltip(m);
        for (Node n : List.of(bar, label)) {
            Tooltip.install(n, new Tooltip(tip));
            n.setOnMouseClicked(e -> onOpen.accept(m));
        }
        track.place(bar, from, to, Kind.BAR);
        track.place(label, from, to, Kind.LABEL);
        var row = row(nameCell, track, ROW_HEIGHT);
        row.getStyleClass().add("timeline-row");
        return row;
    }

    /** A track with the column lines (and weekend shading in days zoom). */
    private Track gridTrack() {
        var track = new Track();
        for (Column c : scale.columns()) {
            var cell = new Region();
            cell.getStyleClass().add("timeline-column");
            DayOfWeek day = c.start().getDayOfWeek();
            if (scale.zoom() == Zoom.DAYS && (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)) {
                cell.getStyleClass().add("timeline-weekend");
            }
            track.place(cell, scale.fraction(c.start()), scale.fraction(c.endExclusive()), Kind.FILL);
        }
        return track;
    }

    private void addToday(Track track) {
        if (scale.contains(today)) {
            var line = new Region();
            line.getStyleClass().add("timeline-today");
            line.setMouseTransparent(true);
            double mid = (scale.fraction(today) + scale.fraction(today.plusDays(1))) / 2;
            track.place(line, mid, mid, Kind.LINE);
        }
    }

    private static HBox row(Region nameCell, Track track, double height) {
        nameCell.setMinWidth(NAME_WIDTH);
        nameCell.setPrefWidth(NAME_WIDTH);
        nameCell.setMaxWidth(NAME_WIDTH);
        nameCell.getStyleClass().add("timeline-name");
        HBox.setHgrow(track, Priority.ALWAYS);
        var row = new HBox(nameCell, track);
        row.setMinHeight(height);
        row.setPrefHeight(height);
        return row;
    }

    private enum Kind { FILL, LINE, BAR, LABEL }

    private record Span(double from, double to, Kind kind) {
    }

    /** Lays children out by their date fractions across its width, so it follows window resizes. */
    private static final class Track extends Pane {

        private static final String SPAN = "timeline.span";
        private static final double BAR_INSET = 5;
        private static final double MIN_BAR = 4;
        private static final double LABEL_PAD = 6;

        Track() {
            setMinWidth(0);
            var clip = new Rectangle();
            clip.widthProperty().bind(widthProperty());
            clip.heightProperty().bind(heightProperty());
            setClip(clip);
        }

        void place(Node node, double from, double to, Kind kind) {
            node.getProperties().put(SPAN, new Span(from, to, kind));
            getChildren().add(node);
        }

        @Override
        protected double computePrefWidth(double height) {
            return 100; // the row's HBox grows it; children never dictate the width
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            for (Node n : getManagedChildren()) {
                if (!(n.getProperties().get(SPAN) instanceof Span s)) {
                    continue;
                }
                double x = s.from() * w;
                double width = (s.to() - s.from()) * w;
                switch (s.kind()) {
                    case FILL -> n.resizeRelocate(x, 0, width, h);
                    case LINE -> n.resizeRelocate(x - 1, 0, 2, h);
                    case BAR -> n.resizeRelocate(x, BAR_INSET, Math.max(MIN_BAR, width), h - 2 * BAR_INSET);
                    case LABEL -> layoutLabel((Label) n, x, Math.max(MIN_BAR, width), w, h);
                }
            }
        }

        private void layoutLabel(Label label, double barX, double barW, double w, double h) {
            double textH = label.prefHeight(-1);
            var p = LabelPlacement.of(barX, barW, label.prefWidth(-1), w, LABEL_PAD);
            label.pseudoClassStateChanged(OUTSIDE, !p.inside());
            label.resizeRelocate(p.x(), (h - textH) / 2, p.width(), textH);
        }

        private static final javafx.css.PseudoClass OUTSIDE = javafx.css.PseudoClass.getPseudoClass("outside");
    }
}
