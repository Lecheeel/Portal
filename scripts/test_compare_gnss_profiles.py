import copy
import unittest

from compare_gnss_profiles import compare, histogram_distance, read_window


def satellite(system=1, svid=1, used=True):
    return dict(constellation=system, svid=svid, usedInFix=used, cn0DbHz=35.0,
                elevationDegrees=30.0, azimuthDegrees=90.0)


def report(records=None, count=61, interval=1000):
    frames = [dict(capturedAtMillis=1000 + i * interval,
                   satellites=copy.deepcopy([satellite()] if records is None else records)) for i in range(count)]
    return dict(device={"model": "test"}, gnssEvidence=dict(schema=1, startedAtMillis=1000,
                endedAtMillis=frames[-1]["capturedAtMillis"], frames=frames))


class GnssComparisonTest(unittest.TestCase):
    def test_unique_identities_not_frequency_count_determine_ratios_and_used_fraction(self):
        records = [satellite(1, 1, False), satellite(1, 1), satellite(6, 1, False)]
        result = compare(read_window(report(records)), read_window(report(records)), iterations=100)
        metrics = result["reference"]["metrics"]
        self.assertEqual(2, metrics["meanVisible"])
        self.assertEqual(.5, metrics["usedFraction"])
        self.assertEqual(.5, metrics["ratio:GPS"])
        self.assertEqual(.5, metrics["ratio:GALILEO"])
        self.assertEqual([0, 0], result["metrics"]["ratio:GPS"]["bootstrapPercentile95"])
        self.assertEqual(0, result["signalJointTotalVariation"])
        self.assertEqual("DESCRIPTIVE_ONLY", result["verdict"])

    def test_independent_analytic_histograms_show_count_and_constellation_shift(self):
        a = read_window(report([satellite()]))
        b = read_window(report([satellite(), satellite(6, 1)]))
        result = compare(a, b, iterations=100)
        self.assertEqual(1, result["visibleCountTotalVariation"])
        self.assertEqual(.5, result["constellationTotalVariation"])
        self.assertEqual(-.5, result["metrics"]["ratio:GPS"]["deltaTrialMinusReference"])
        self.assertEqual([-.5, -.5], result["metrics"]["ratio:GPS"]["bootstrapPercentile95"])
        self.assertEqual(["GALILEO:1"], result["addedIdentities"])
        self.assertEqual(.25, histogram_distance({1: 1, 2: 3}, {1: 2, 2: 2}))

    def test_callback_bursts_use_last_frame_in_each_second(self):
        data = report(count=4, interval=100)
        data["gnssEvidence"]["frames"][-1]["satellites"] = [satellite(6)]
        window = read_window(data)
        self.assertEqual(1, len(window.frames))
        self.assertEqual({("GALILEO", 1)}, window.frames[0].identities)
        result = compare(window, window, 12, iterations=100)
        self.assertIsNone(result["metrics"]["ratio:GALILEO"]["bootstrapPercentile95"])
        self.assertFalse(result["autoModeComparable"])

    def test_old_capture_projection_does_not_claim_signal_distribution_comparability(self):
        data = report()
        evidence = data["gnssEvidence"]
        raw = dict(schemaVersion=1, deviceModel="test", osVersion="test",
                   startedAtMillis=evidence["startedAtMillis"], endedAtMillis=evidence["endedAtMillis"],
                   frames=[dict(capturedAtMillis=f["capturedAtMillis"], satellites=[dict(
                       constellation="GPS", svid=1, usedInFix=True, cn0DbHz=35.0,
                       elevationDeg=30.0, azimuthDeg=90.0)]) for f in evidence["frames"]])
        result = compare(read_window(raw), read_window(data), iterations=100)
        self.assertIsNone(result["signalJointTotalVariation"])
        self.assertEqual(0, result["constellationTotalVariation"])

    def test_empty_sky_and_too_few_long_blocks_withhold_intervals(self):
        empty = read_window(report([]))
        result = compare(empty, empty, iterations=100)
        self.assertIsNone(result["metrics"]["usedFraction"]["deltaTrialMinusReference"])
        self.assertIsNone(result["metrics"]["meanVisible"]["bootstrapPercentile95"])
        window = read_window(report())
        result = compare(window, window, iterations=100, block_seconds=60)
        self.assertEqual(2, result["reference"]["temporalBlocks"])
        self.assertIsNone(result["metrics"]["meanVisible"]["bootstrapPercentile95"])

    def test_invalid_or_missing_observations_are_rejected_instead_of_filtered(self):
        for field, value in [("cn0DbHz", "NaN"), ("elevationDegrees", -1), ("azimuthDegrees", 360),
                             ("svid", 0), ("usedInFix", "true"), ("constellation", 10)]:
            data = report()
            data["gnssEvidence"]["frames"][0]["satellites"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                read_window(data)
        with self.assertRaises(ValueError):
            read_window({"checks": []})
        data = report()
        data["gnssEvidence"]["frames"].reverse()
        with self.assertRaises(ValueError):
            read_window(data)

    def test_zero_cn0_placeholders_do_not_bias_distribution_or_bootstrap_eligibility(self):
        zero = {**satellite(6), "cn0DbHz": 0.0}
        mixed = report([satellite(), zero])
        original = copy.deepcopy(mixed)
        result = compare(read_window(report()), read_window(mixed), iterations=100)
        self.assertEqual(original, mixed)  # Input evidence remains intact.
        self.assertEqual(1, result["trial"]["metrics"]["meanVisible"])
        self.assertEqual(0, result["trial"]["metrics"]["ratio:GALILEO"])
        self.assertEqual([], result["addedIdentities"])
        self.assertEqual(0, result["signalJointTotalVariation"])
        empty = read_window(report([zero]))
        result = compare(empty, empty, iterations=100)
        self.assertIsNone(result["constellationTotalVariation"])
        self.assertIsNone(result["metrics"]["meanVisible"]["bootstrapPercentile95"])
        invalid = report([{**zero, "azimuthDegrees": 360}])
        with self.assertRaises(ValueError):
            read_window(invalid)


if __name__ == "__main__":
    unittest.main()
