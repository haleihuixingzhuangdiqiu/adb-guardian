# -*- coding: utf-8 -*-
import unittest
from desktop.adb_guardian import parse_devices, parse_mdns, matches_name, valid_selector


class MdnsCollisionTests(unittest.TestCase):
    def test_bonjour_collision_names_preserve_target_identity(self):
        name = "adb-TEST123-AbCd (2)._adb-tls-connect._tcp"
        self.assertEqual(name, valid_selector(name))
        self.assertEqual({name: "device"}, parse_devices(name + " device product:test transport_id:1\n"))
        services = parse_mdns("adb-TEST123-AbCd (2) _adb-tls-connect._tcp 192.0.2.10:45001\n")
        self.assertEqual(1, len(services))
        self.assertEqual("adb-TEST123-AbCd (2)", services[0].name)
        self.assertTrue(matches_name(services[0].name, {"serialno": "TEST123"}))
        self.assertFalse(matches_name(services[0].name, {"serialno": "TEST12"}))

    def test_collision_name_does_not_make_pairing_service_a_connection(self):
        services = parse_mdns("adb-TEST123 (2) _adb-tls-pairing._tcp 192.0.2.10:45002\n")
        self.assertEqual("_adb-tls-pairing._tcp", services[0].kind)


if __name__ == "__main__":
    unittest.main()
