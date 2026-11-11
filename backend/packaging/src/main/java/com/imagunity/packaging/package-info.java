/**
 * Empaquetage d'une étude (phase 3).
 *
 * <p>{@link com.imagunity.packaging.StudyPackager} produit un ou plusieurs paquets {@code .tar.zst}
 * (instances DICOM octet pour octet, compression zstd sans perte, manifeste avec une empreinte
 * SHA-256 par instance), découpés par série au-delà de la taille maximale.
 * {@link com.imagunity.packaging.PackageReader} les dépaquette et vérifie leur intégrité.
 */
package com.imagunity.packaging;
