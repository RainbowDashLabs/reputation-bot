package de.chojo.repbot.dao.snapshots.statistics;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

public record CommandsStatistic(LocalDate date, List<CommandStatistic> commands) implements ChartProvider {
    @Override
    public byte[] getChart() {
        var categorySeries = new CategoryChartBuilder().width(800).height(600)
                .title("Commands Statistic for " + date(date()))
                .xAxisTitle("Command")
                .yAxisTitle("Count")
                .theme(Styler.ChartTheme.Matlab)
                .build();
        categorySeries.setCustomXAxisTickLabelsFormatter(r -> commands.get(r.intValue()).command());
        categorySeries.addSeries("Command",
                        IntStream.range(0, commands.size()).mapToObj(Double::valueOf).toList(),
                        commands.stream().map(CommandStatistic::count).map(Double::valueOf).toList())
                .setMarker(SeriesMarkers.NONE)
                .setLabel("command");

        try {
            return BitmapEncoder.getBitmapBytes(categorySeries, BitmapEncoder.BitmapFormat.PNG);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
