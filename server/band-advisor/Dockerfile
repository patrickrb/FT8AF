# ft8af-band-advisor
#
# Stage 1 builds voacapl (James Watson's GPL Linux port of NTIA/ITS VOACAP,
# https://github.com/jawatson/voacapl) from source. It is invoked by the app
# as an EXTERNAL binary via subprocess — never linked or vendored.
#
# NOTE(scaffold): the voacapl build follows the project's autotools + gfortran
# conventions (./configure && make && make install, then `make makeitshfbc`
# to install the itshfbc data tree). Verify against the repo README when
# first building this image; pin VOACAPL_REF to a release tag for
# reproducibility.

# ----------------------------------------------------------------- stage 1
FROM debian:bookworm-slim AS voacapl-build

ARG VOACAPL_REF=master

RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates build-essential gfortran automake autoconf make \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone --depth 1 --branch "${VOACAPL_REF}" \
        https://github.com/jawatson/voacapl.git

WORKDIR /build/voacapl
# voacapl ships an autotools build; makeitshfbc installs the itshfbc data
# directory (ionospheric coefficients, antenna files) into $HOME/itshfbc.
RUN ./configure && make && make install \
    && HOME=/build make makeitshfbc

# ----------------------------------------------------------------- stage 2
FROM python:3.12-slim AS runtime

# gfortran runtime library for the voacapl binary
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgfortran5 curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 advisor

COPY --from=voacapl-build /usr/local/bin/voacapl /usr/local/bin/voacapl
COPY --from=voacapl-build /build/itshfbc /opt/itshfbc

WORKDIR /srv/band-advisor
COPY pyproject.toml README.md ./
COPY app ./app
RUN pip install --no-cache-dir .

# voacapl writes run decks under <itshfbc>/run — that dir must be writable.
RUN mkdir -p /opt/itshfbc/run /var/lib/band-advisor /etc/band-advisor \
    && chown -R advisor:advisor /opt/itshfbc/run /var/lib/band-advisor

ENV VOACAPL_PATH=/usr/local/bin/voacapl \
    ITSHFBC_PATH=/opt/itshfbc \
    FEATURE_CONFIG_PATH=/etc/band-advisor/feature-config.json \
    BASELINE_PATH=/var/lib/band-advisor/baseline.json

USER advisor
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/healthz || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
