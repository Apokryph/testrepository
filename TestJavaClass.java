//author: Kobs

package org.wickedsource.coderadar.score.rest.commitscore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wickedsource.coderadar.commit.domain.Commit;
import org.wickedsource.coderadar.core.rest.validation.NoMetricValuesFoundException;
import org.wickedsource.coderadar.metric.domain.metricvalue.MetricValueDTO;
import org.wickedsource.coderadar.metric.domain.metricvalue.MetricValueRepository;
import org.wickedsource.coderadar.score.domain.scoreprofile.ScoreProfile;
import org.wickedsource.coderadar.score.domain.scoreprofile.ScoreProfileMetric;
import org.wickedsource.coderadar.score.domain.scoreprofile.ScoreProfileMetricRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class CommitScoreService {

    private ScoreProfileMetricRepository scoreProfileMetricRepository;
    private MetricValueRepository metricValueRepository;

    @Autowired
    public CommitScoreService(ScoreProfileMetricRepository scoreProfileMetricRepository, MetricValueRepository metricValueRepository) {
        this.scoreProfileMetricRepository = scoreProfileMetricRepository;
        this.metricValueRepository = metricValueRepository;
    }

    public Long getScoreValueFromCommitAndProfile(Commit commit, ScoreProfile scoreProfile) {

        // List of all metrics in profile
        List<ScoreProfileMetric> scoreProfileMetrics = scoreProfile.getMetrics();

        if (scoreProfileMetrics == null) {
            throw new IllegalArgumentException("'scoreProfiles' not found!");
        }

        // List of all metricNames in profile
        List<String> scoreProfileMetricNames = new ArrayList<>();

        for (ScoreProfileMetric metric : scoreProfileMetrics) {
            scoreProfileMetricNames.add(metric.getName());
        }

        // List of all metric values
        List<MetricValueDTO> profileMetricValues = metricValueRepository.findValuesAggregatedByCommitAndMetric(commit.getProject().getId(), commit.getSequenceNumber(), scoreProfileMetricNames);

        // Number of files in commit
        List<Long> fileCountsInCommit = metricValueRepository.countFilesPerCommitAndMetric(commit.getProject().getId(), commit.getSequenceNumber(), scoreProfileMetricNames);

        // Divide the aggregated metric values by the number of files to get the average
        if (profileMetricValues.isEmpty()) {
            throw new NoMetricValuesFoundException();
        }

        for (int i = 0; i < profileMetricValues.size(); i++) {
            long aggregatedValue = profileMetricValues.get(i).getValue();
            long fileCount = fileCountsInCommit.get(i);
            long result = aggregatedValue / fileCount;

            profileMetricValues.set(i, new MetricValueDTO(profileMetricValues.get(i).getMetric(), result));
        }

        // Use metric value average for every metric to calculate commitscore
        return getScoreValueFromMetricValues(scoreProfile, profileMetricValues);
    }

    private Long getScoreValueFromMetricValues(ScoreProfile scoreProfile, List<MetricValueDTO> metricValues) {

        float scoreSum = 0.0f; // Sum of all calculated commitscore values
        long weightSum = 0; // Sum of all commitscore weights

        // Calculate sum of all scores and weights per metric
        for (MetricValueDTO metricValue : metricValues) {
            ScoreProfileMetric scoreProfileMetric = scoreProfileMetricRepository.findByNameAndProfile(metricValue.getMetric(), scoreProfile);

            scoreSum += calculateScoreMetricValue(scoreProfileMetric, metricValue.getValue());
            weightSum += scoreProfileMetric.getScoreMetricWeight();
        }

        // Divide sum of scores by sum of weights to get average
        // After multiply by 100 to get a commitscore value between 0-100
        return (long) ((scoreSum / weightSum) * 100);
    }

    private float calculateScoreMetricValue(ScoreProfileMetric metric, long value) {
        long minRange, maxRange; // minimum and maximum of allowed commitscore range
        float numerator, divisor, range, scorePoints; // help variables to calculate the final commitscore value

        // calculate commitscore differently for every commitscore type, COMPLIANCE is positive and VIOLATION is negative
        switch (metric.getMetricType()) {
            case COMPLIANCE:

                minRange = metric.getScoreFailValue();
                maxRange = metric.getScoreOptimalValue();

                value = clampValue(value, minRange, maxRange);

                numerator = value - minRange;
                divisor = maxRange - minRange;
                range = numerator / divisor;

                scorePoints = range * metric.getScoreMetricWeight();

                return scorePoints;

            case VIOLATION:
                minRange = metric.getScoreOptimalValue();
                maxRange = metric.getScoreFailValue();

                value = clampValue(value, minRange, maxRange);

                numerator = maxRange - value;
                divisor = maxRange - minRange;
                range = numerator / divisor;

                scorePoints = range * metric.getScoreMetricWeight();

                return scorePoints;

            default:
                throw new IllegalStateException(
                        String.format("unsupported ScoreMetricType: %s", metric.getMetricType()));
        }
    }

    private long clampValue(long value, long min, long max) {
        if (value > max) {
            return max;
        }
        if (value < min) {
            return min;
        }

        return value;
    }
}
