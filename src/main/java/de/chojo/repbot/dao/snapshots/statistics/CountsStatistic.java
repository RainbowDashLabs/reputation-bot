package de.chojo.repbot.dao.snapshots.statistics;

import de.chojo.repbot.util.TimeFormatter;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.style.AxesChartStyler;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

public record CountsStatistic(List<CountStatistics> stats) implements ChartProvider {


    public CountStatistics get(int index) {
        if (stats.isEmpty()) {
            return new CountStatistics(LocalDate.MIN, 0);
        }
        return stats.get(index);
    }

    @Override
    public byte[] getChart(String title) {
        var categorySeries = new CategoryChartBuilder().width(1200).height(600)
                .title(title)
                .xAxisTitle("Date")
                .yAxisTitle("Count")
                .theme(Styler.ChartTheme.Matlab)
                .build();

        var styler = categorySeries.getStyler();
        styler.setLegendVisible(false);
        styler.setXAxisLabelRotation(20);
        styler.setXAxisLabelAlignmentVertical(AxesChartStyler.TextAlignment.Right);
        styler.setXAxisLabelAlignment(AxesChartStyler.TextAlignment.Right);

        var sorted = stats.stream().sorted().toList();

        categorySeries.addSeries("Counts",
                        sorted.stream().map(countStatistics -> TimeFormatter.date(countStatistics.date())).toList(),
                        sorted.stream().map(CountStatistics::count).toList())
                .setMarker(SeriesMarkers.NONE)
                .setLabel("Counts");

        try {
            return BitmapEncoder.getBitmapBytes(categorySeries, BitmapEncoder.BitmapFormat.PNG);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
