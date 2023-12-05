# 0.0.66 (2023-12-05 / 9ce088a)

## Added
- Extend the Connection protocol to clojure.lang.Atom, so that we can have 
  connection in immutable object or mutable object. With connection object 
  mutable, we can refresh it when the token expires.

## Fixed

## Changed


# 0.0.56 (2023-04-14 / fd0bc4a)

## Added

## Fixed

## Changed

- [breaking] Support only metabase version >= `0.46.1`
- Change the API call parameters on `/api/dashboard/:id/cards` 

# 0.0.50 (2023-01-19 / 8e058ff)

## Added

- Let (setup/init-metabase! config) support first-name, last-name, site-name.

## Fixed

## Changed

# 0.0.45 (2022-11-24 / f6273b8)

## Added

- Add fetch-users API

## Fixed

## Changed

- [breaking] Support only metabase version >= `0.40.0`
- Support the pagination feature of `/api/user` [Ref](https://github.com/metabase/metabase/wiki/What%27s-new-in-0.40.0-for-Metabase-REST-API-clients)

# 0.0.24 (2022-11-13 / 23678d6)

## Added

- Add setup automation

# 0.0.12 (2021-10-28 / 756d3d1)

## Added

- First release