{
  inputs.flake-utils.url = "github:numtide/flake-utils";
  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system}; prev = pkgs; in
      rec {
        packages.gwt = pkgs.gwt240.overrideAttrs (old: with pkgs; rec {
          version = "2.8.2";  # same one as in dev.sh
          src = fetchurl {
            #url = "https://storage.googleapis.com/google-code-archive-downloads/v2/code.google.com/google-web-toolkit/gwt-${version}.zip";
            url = "https://storage.googleapis.com/gwt-releases/gwt-${version}.zip";
            hash = "sha256-lwcB2sxVFwCI9esycTfLSnWB67RzQYjfzC+tmUF0XRs=";
          };
          installPhase = ''
            mkdir -p $out
            unzip $src
            mv gwt-${version} $out/bin
          '';
        });
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            #gwt240
            packages.gwt
            #openjdk8_headless  -> it wants awt
            openjdk8
            ant
            gnused
            python3
            bashInteractive  # dev.sh uses compgen
          ];

          shellHook = ''
            export GWT_DIR=${packages.gwt}/bin

            if [ ! -e build.xml ] ; then
              echo "Run to create build.xml: bash dev.sh setup"
            fi
            # Remember that we don't want to use the codeserver and webserver in dev.sh
            # because they don't work so well (needs two commands, codeserver doesn't show Java errors).
            echo "Run to start dev server: ant devmode"
            alias dev='ant devmode'

            # Publish to sim.unwahrhe.it - this will only work for me, of course, unless you change the target.
            export PUBLISH_TARGET=unwahrheit::sim/
            alias publish='ant build && rsync --rsh=ssh --exclude="*.class" --exclude="WEB-INF/classes/com/lushprojects/circuitjs1/public" -rav war/* $PUBLISH_TARGET'
          '';
        };
      }
    );
}
