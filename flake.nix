{
  description = "The Docker LSP";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    devshell = {
      url = "github:numtide/devshell";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, clj-nix, devshell }:

    flake-utils.lib.eachDefaultSystem
      (system:
        let
          overlays = [
            devshell.overlays.default
            (self: super: {
              clj-nix = clj-nix.packages."${system}";
            })
          ];
          # don't treat pkgs as meaning nixpkgs - treat it as all packages!
          pkgs = import nixpkgs {
            inherit overlays system;
          };
        in
        {
          packages = rec {
            clj = pkgs.clj-nix.mkCljBin {
              name = "json-rpc";
              projectSrc = ./.;
              main-ns = "json-rpc.server";
              buildCommand = "clj -T:build uber";
            };
            deps-cache = pkgs.clj-nix.mk-deps-cache {
              lockfile = ./deps-lock.json;
            };
            graal = pkgs.clj-nix.mkGraalBin {
              # lazy lookup of a derivation that will exist
              cljDrv = self.packages."${system}".clj;
              graalvmXmx = "-J-Xmx8g";
              extraNativeImageBuildArgs = [
                "--native-image-info"
                "--initialize-at-build-time"
                "--enable-http"
                "--enable-https"
              ];
            };
            default = pkgs.buildEnv {
              name = "install";
              paths = [
                graal
              ];
            };
          };

          devShells.default = pkgs.devshell.mkShell {
            name = "lsp";
            packages = with pkgs; [ babashka clojure node2nix nodejs ];

            commands = [
              {
                name = "lock-clojure-deps";
                help = "update deps-lock.json whenever deps.edn changes";
                command = "nix run /Users/slim/slimslenderslacks/clj-nix#deps-lock";
              }
            ];
          };
        });
}
