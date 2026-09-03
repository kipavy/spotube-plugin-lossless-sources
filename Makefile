.PHONY: all check compile test archive verify

# Bare `make` builds the first target, so it must do the real work.
all: check compile

check:
	python3 check_scope.py

compile:
	hetu compile src/plugin.ht build/plugin.out

# Runs the compiled bytecode against fake sources. Catches what compiling
# cannot: undefined identifiers, binding type mismatches, routing, failover
# and the 202 retry. Needs build/plugin.out, so compile first.
test:
	harness/setup.sh && cd harness && dart pub get && dart run bin/run_test.dart

# Chained with && so a missing plugin.out fails the build instead of quietly
# packaging an archive without any bytecode in it.
archive:
	mkdir -p build/archive && \
	cp plugin.json build/plugin.out assets/logo.png build/archive/ && \
	cd build/archive && \
	zip -r plugin.zip ./ && \
	cd ../.. && \
	mv build/archive/plugin.zip build/plugin.smplug

verify:
	python3 -c "import zipfile,sys; \
names=zipfile.ZipFile('build/plugin.smplug').namelist(); \
missing=[f for f in ('plugin.json','plugin.out','logo.png') if f not in names]; \
print('package contents:', names); \
sys.exit('MISSING FROM PACKAGE: %s' % missing) if missing else print('package OK')"
