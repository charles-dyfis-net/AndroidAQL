{
  description = "AirSlice development, screenshot, and Fastlane Play Store toolchain";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-darwin"
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          pythonWithPillow = pkgs.python3.withPackages (ps: [ ps.pillow ]);
        in
        {
          default = pkgs.mkShell {
            name = "airslice-dev";

            packages = [
              pkgs.fastlane
              pythonWithPillow
              pkgs.ffmpeg
              pkgs.uv
              pkgs.oxipng
              pkgs.pngquant
              pkgs.resvg
              pkgs.dejavu_fonts
            ];

            shellHook = ''
              PS1="[airslice-shell] $PS1"
              export AIRSLICE_FONT_DIR="${pkgs.dejavu_fonts}/share/fonts/truetype"
              if [ -z "''${ANDROID_HOME:-}" ]; then
                if [ -d "$HOME/Library/Android/sdk" ]; then
                  export ANDROID_HOME="$HOME/Library/Android/sdk"
                elif [ -d "$HOME/Android/Sdk" ]; then
                  export ANDROID_HOME="$HOME/Android/Sdk"
                fi
              fi
            '';
          };
        });
    };
}
