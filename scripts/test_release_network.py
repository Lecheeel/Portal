import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from release_policy import open_release_url

class ReleaseNetworkTest(unittest.TestCase):
    @patch('release_policy.time.sleep')
    @patch('release_policy.urllib.request.urlopen')
    def test_transient_gateway_error_retries(self, open_url, sleep):
        response = object()
        open_url.side_effect = [HTTPError('https://example.test', 504, 'timeout', {}, None), response]
        self.assertIs(response, open_release_url('https://example.test'))
        self.assertEqual(2, open_url.call_count)
        sleep.assert_called_once_with(1)

    @patch('release_policy.time.sleep')
    @patch('release_policy.urllib.request.urlopen')
    def test_permanent_failure_is_not_hidden(self, open_url, sleep):
        open_url.side_effect = HTTPError('https://example.test', 404, 'missing', {}, None)
        with self.assertRaises(HTTPError): open_release_url('https://example.test')
        self.assertEqual(1, open_url.call_count)
        sleep.assert_not_called()

    @patch('release_policy.time.sleep')
    @patch('release_policy.urllib.request.urlopen')
    def test_transient_retries_remain_bounded(self, open_url, sleep):
        open_url.side_effect = HTTPError('https://example.test', 504, 'timeout', {}, None)
        with self.assertRaises(HTTPError): open_release_url('https://example.test')
        self.assertEqual(3, open_url.call_count)
