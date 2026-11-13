"""Centralized PSK Reporter integration.

Phones NEVER poll retrieve.pskreporter.info directly — this service is the
single aggregation point, so one fetch per 2-char field per 5 minutes serves
every nearby user.  Etiquette implemented here: gzip, appcontact parameter,
>=5-minute per-parameter-set interval, 15-minute backoff on 429/503.
"""
