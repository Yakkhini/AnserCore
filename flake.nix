# SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
# SPDX-License-Identifier: MulanPSL-2.0
{
  description = "Flake for AnserCore project";

  inputs = {
    flake-parts.url = "github:hercules-ci/flake-parts";
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    devshell.url = "github:numtide/devshell";
    git-hooks-nix.url = "github:cachix/git-hooks.nix";
    treefmt-nix.url = "github:numtide/treefmt-nix";
  };

  outputs = inputs @ {flake-parts, ...}:
    flake-parts.lib.mkFlake {inherit inputs;} {
      imports = [
        # To import an internal flake module: ./other.nix
        # To import an external flake module:
        #   1. Add foo to inputs
        #   2. Add foo as a parameter to the outputs function
        #   3. Add here: foo.flakeModule
        inputs.devshell.flakeModule
        inputs.git-hooks-nix.flakeModule
        inputs.treefmt-nix.flakeModule
      ];
      systems = ["x86_64-linux" "aarch64-linux" "aarch64-darwin" "x86_64-darwin"];
      perSystem = {
        config,
        self',
        inputs',
        pkgs,
        system,
        ...
      }: {
        # Per-system attributes can be defined here. The self' and inputs'
        # module parameters provide easy access to attributes of the same
        # system.

        # Equivalent to  inputs'.nixpkgs.legacyPackages.hello;
        packages.default = pkgs.hello;
        devshells.default = {
          name = "ansercore-devshell";

          devshell.startup.pre-commit.text = config.pre-commit.installationScript;

          packages = [
            pkgs.reuse

            (pkgs.python3.withPackages (ps: with ps; [sphinx myst-parser furo]))
          ];
        };
        pre-commit = {
          settings = {
            package = pkgs.prek;
            hooks = {
              reuse = {
                enable = true;
                excludes = ["flake.lock"];
              };
              treefmt.enable = true;
            };
          };
        };
        treefmt = {
          programs.alejandra.enable = true;
        };
      };
      flake = {
        # The usual flake attributes can be defined here, including system-
        # agnostic ones like nixosModule and system-enumerating ones, although
        # those are more easily expressed in perSystem.
      };
    };
}
